package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.AssetCalibration;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.C1Level;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.Estimate;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.KSummary;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.MarginResult;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.NoMargin;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.Onboarding;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.ProfileCalibration;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.Recommendation;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.TestPoint;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.Windows;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyStudy.ConstantPick;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * Étape C3 : calage de l'exposition par profil sur le PIRE CAS, avec une marge de sécurité MESURÉE. Un seul levier (multiplicateur du montant
 * de base du lien, comme C1 / C2) ; preset, Trend, modulation inchangés. Hors ligne : rien n'est appliqué ni persisté.
 * <ul>
 *   <li>Pire cas : pour chaque niveau, drawdown de toutes les fenêtres glissantes de 24 mois (pas de 30 jours, la dernière finissant à
 *   {@code lastEnd}) et de l'historique complet ; le pire est le drawdown de calage du niveau. Niveau sans marge = premier franchissement du
 *   plafond en montant la grille, interpolé comme en C1 ({@link ExposurePolicyStudy#constantMultiplier}).</li>
 *   <li>Marge : pour chaque date T (tous les 3 mois), calage ancré (SEULES les fenêtres et l'historique terminés avant T) avec le plafond / k,
 *   puis replay du niveau retenu pendant les 12 mois suivant T ; r(T) = drawdown réel / plafond.</li>
 * </ul>
 * Déterministe : le pool ne change que la durée (résultats consommés dans l'ordre de soumission).
 */
public final class ExposureCalibrationStudy {

    private static final double EPS = 1e-9;
    /** Écart toléré (jours) entre une plage demandée et la plage réellement rejouée : au-delà, la plage est tronquée. */
    private static final int DAY_TOL = 3;

    private ExposureCalibrationStudy() {
    }

    /** Fenêtre glissante complète ({@code from} et {@code to} inclus). */
    public record Window(int index, LocalDate from, LocalDate to) {
        public String label() {
            return from + " → " + to;
        }
    }

    private record Cell(Line line, boolean complete) {
    }

    /** Drawdowns par niveau d'une plage ; {@code dd} rempli de NaN si la plage n'est pas rejouable ou tronquée. */
    private record Dd(double[] dd, boolean replayable, boolean complete) {
    }

    private record Eval(Line[] blocks, Line full) {
    }

    // ------------------------------------------------------------------ fenêtres, estimation

    /**
     * Fenêtres de {@code months} mois, une tous les {@code stepDays} jours, la dernière finissant à {@code lastEnd}, de la plus ancienne à la plus
     * récente ; une fenêtre qui commencerait avant {@code firstDay} (fin du préchauffage) n'existe pas : aucune fenêtre tronquée.
     */
    static List<Window> windows(LocalDate lastEnd, int months, int stepDays, LocalDate firstDay) {
        List<Window> newestFirst = new ArrayList<>();
        for (int k = 0; ; k++) {
            LocalDate to = lastEnd.minusDays((long) stepDays * k);
            LocalDate from = to.minusMonths(months).plusDays(1);
            if (from.isBefore(firstDay)) {
                break;
            }
            newestFirst.add(new Window(0, from, to));
        }
        List<Window> out = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            out.add(new Window(out.size(), newestFirst.get(i).from(), newestFirst.get(i).to()));
        }
        return out;
    }

    /** Fenêtres de cet actif (premier jour rejouable lu sur l'évaluateur de l'historique complet). */
    public static List<Window> windowsFor(ScorerInputs in, ExposureCalibrationConfig cfg) {
        SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(cfg.lastEnd()), cfg.windowMonths());
        LocalDate first = ev.firstReplayableDay(ev.template().sets());
        if (first == null) {
            throw new IllegalStateException("historique insuffisant : aucun jour rejouable");
        }
        return windows(cfg.lastEnd(), cfg.windowMonths(), cfg.windowStepDays(), first);
    }

    /** Rejeux au pire pour un actif : tables (fenêtres, historiques ancrés, blocs de C1), tests de T, évaluations finales. */
    public static Estimate estimate(ExposureCalibrationConfig cfg, int windowCount) {
        long levels = cfg.grid().size();
        long profiles = FrontierConfig.profileCaps().size();
        int anchors = cfg.anchors().size();
        long tables = levels * (windowCount + anchors + 1L + cfg.parity().blockCount());
        long tests = (long) anchors * profiles * cfg.margins().size();
        long evals = profiles * 2L * (cfg.parity().blockCount() + 1L);
        long total = tables + tests + evals;
        return new Estimate(total, cfg.parity().replayMs(), total * cfg.parity().replayMs() / 60_000.0);
    }

    // ------------------------------------------------------------------ mesures

    private static Cell cell(SensitivityEvaluator ev, boolean full, RainbowAtrParamSet[] sets, double multiplier, double ref, LocalDate from,
                             LocalDate to) {
        Parity p = ev.parity(sets, full, 0, null, ExposureFrontier.linkBase(multiplier, ref), null);
        if (p == null) {
            return null;
        }
        boolean complete = full || (!p.from().isAfter(from.plusDays(DAY_TOL)) && !p.to().isBefore(to.minusDays(DAY_TOL)));
        return new Cell(p.preset(), complete);
    }

    private static Dd drawdowns(SensitivityEvaluator ev, boolean full, RainbowAtrParamSet[] sets, List<Double> grid, double ref, LocalDate from,
                                LocalDate to) {
        double[] dd = new double[grid.size()];
        boolean complete = true;
        for (int l = 0; l < dd.length; l++) {
            Cell c = cell(ev, full, sets, grid.get(l), ref, from, to);
            if (c == null) {
                Arrays.fill(dd, Double.NaN);
                return new Dd(dd, false, true);
            }
            dd[l] = c.line().maxDrawdownPct();
            complete &= c.complete();
        }
        if (!complete) {
            Arrays.fill(dd, Double.NaN);
        }
        return new Dd(dd, true, complete);
    }

    // ------------------------------------------------------------------ calage (purs)

    /**
     * Drawdown de calage par niveau : le pire (min) des drawdowns des fenêtres terminées STRICTEMENT avant {@code cutoffExclusive} et de
     * l'historique complet {@code fullDd} (lui-même terminé avant le cutoff). Ne lit aucune fenêtre qui finit à partir du cutoff. Cellule NaN =
     * plage non rejouable, ignorée ; {@code null} si aucune plage ne contribue.
     */
    static double[] worstByLevel(List<Window> windows, double[][] windowDd, double[] fullDd, LocalDate cutoffExclusive) {
        int n = fullDd.length;
        double[] worst = new double[n];
        boolean any = false;
        for (int w = 0; w < windows.size(); w++) {
            if (!windows.get(w).to().isBefore(cutoffExclusive)) {
                continue;
            }
            for (int l = 0; l < n; l++) {
                double v = windowDd[w][l];
                if (!Double.isNaN(v)) {
                    worst[l] = Math.min(worst[l], v);
                    any = true;
                }
            }
        }
        for (int l = 0; l < n; l++) {
            if (!Double.isNaN(fullDd[l])) {
                worst[l] = Math.min(worst[l], fullDd[l]);
                any = true;
            }
        }
        return any ? worst : null;
    }

    /** Niveau retenu pour le plafond {@code cap / k} (k = 1 : plafond brut), premier franchissement interpolé comme en C1. */
    static ConstantPick pick(List<Double> grid, double[] worst, double cap, double k) {
        return ExposurePolicyStudy.constantMultiplier(grid, worst, cap / k);
    }

    /** Indice du premier niveau qui franchit le plafond {@code cap / k} ({@code -1} si aucun) ; borne haute de l'encadrement. */
    private static int crossing(double[] worst, double cap, double k) {
        for (int i = 0; i < worst.length; i++) {
            if (worst[i] < cap / k) {
                return i;
            }
        }
        return -1;
    }

    /** r = drawdown réel / plafond (les deux ≤ 0) : r ≤ 1 = plafond tenu. */
    static double ratio(double drawdownPct, double capPct) {
        return Math.abs(capPct) < EPS ? Double.NaN : drawdownPct / capPct;
    }

    static boolean holds(double drawdownPct, double capPct) {
        return drawdownPct >= capPct - EPS;
    }

    /** Indice du plus petit k dont la pire part de T tenus atteint {@code threshold} ({@code -1} si aucun) ; NaN = non évaluable. */
    static int firstSufficient(double[] minShareByK, double threshold) {
        for (int i = 0; i < minShareByK.length; i++) {
            if (!Double.isNaN(minShareByK[i]) && minShareByK[i] >= threshold - EPS) {
                return i;
            }
        }
        return -1;
    }

    /** Autonomie (jours) dont la proposition d'onboarding donne la base {@code multiplier × ref} : cash / (autonomie × multiplicateur moyen) inversé. */
    static Double equivalentAutonomy(double cash, Double mean, double multiplier, double ref) {
        return mean == null || mean <= 0 || multiplier <= 0 || ref <= 0 ? null : cash / (mean * multiplier * ref);
    }

    private static Double mean(List<Double> v) {
        return v.isEmpty() ? null : v.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    // ------------------------------------------------------------------ exécution

    private record Anchor(int index, LocalDate day, LocalDate testTo) {
    }

    private record TestKey(int anchor, double multiplier) {
    }

    /** Mesure un actif : tables, calage ancré × marges × profils, tests, calage final, équivalence avec l'onboarding. */
    public static AssetCalibration run(ScorerInputs in, ExposureCalibrationConfig cfg, ExecutorService pool) {
        long t0 = System.nanoTime();
        ParityConfig pc = cfg.parity();
        LocalDate lastEnd = cfg.lastEnd();
        int months = cfg.windowMonths();
        SensitivityEvaluator fullEv = new SensitivityEvaluator(in.withEndDate(lastEnd), months);
        RainbowAtrParamSet[] sets = fullEv.template().sets();
        double ref = fullEv.referenceBase(sets);
        LocalDate firstDay = fullEv.firstReplayableDay(sets);
        if (firstDay == null) {
            throw new IllegalStateException("historique insuffisant : aucun jour rejouable");
        }
        List<Double> grid = cfg.grid();
        int nl = grid.size();
        List<Window> wins = windows(lastEnd, months, cfg.windowStepDays(), firstDay);
        List<StartingProfile> profiles = new ArrayList<>(FrontierConfig.profileCaps().keySet());
        int np = profiles.size();
        int nk = cfg.margins().size();
        List<LocalDate> days = cfg.anchors();
        List<Anchor> anchors = new ArrayList<>();
        for (LocalDate d : days) {
            anchors.add(new Anchor(anchors.size(), d, cfg.testEnd(d)));
        }
        int na = anchors.size();

        // --- tables : fenêtres glissantes, historiques complets (ancrés à T − 1 puis à lastEnd), blocs de C1
        List<SensitivityEvaluator> winEvs = wins.stream().map(w -> new SensitivityEvaluator(in.withEndDate(w.to()), months, w.from())).toList();
        List<Callable<Dd>> winTasks = new ArrayList<>();
        for (int w = 0; w < wins.size(); w++) {
            final int wi = w;
            winTasks.add(() -> drawdowns(winEvs.get(wi), false, sets, grid, ref, wins.get(wi).from(), wins.get(wi).to()));
        }
        List<Dd> winRaw = ExposurePolicyStudy.ordered(winTasks, pool);
        double[][] windowDd = new double[wins.size()][];
        int truncated = 0;
        int notReplayable = 0;
        for (int w = 0; w < wins.size(); w++) {
            windowDd[w] = winRaw.get(w).dd();
            truncated += winRaw.get(w).replayable() && !winRaw.get(w).complete() ? 1 : 0;
            notReplayable += winRaw.get(w).replayable() ? 0 : 1;
        }
        List<SensitivityEvaluator> fullEvs = new ArrayList<>();
        for (Anchor a : anchors) {
            fullEvs.add(new SensitivityEvaluator(in.withEndDate(a.day().minusDays(1)), months));
        }
        fullEvs.add(fullEv);
        List<Callable<Dd>> fullTasks = new ArrayList<>();
        for (SensitivityEvaluator ev : fullEvs) {
            fullTasks.add(() -> drawdowns(ev, true, sets, grid, ref, null, null));
        }
        List<Dd> fullRaw = ExposurePolicyStudy.ordered(fullTasks, pool);
        double[][] fullDd = fullRaw.stream().map(Dd::dd).toArray(double[][]::new);

        List<Block> specs = TuneBlocks.build(lastEnd, pc.blockMonths(), pc.blockCount());
        List<SensitivityEvaluator> blockEvs = specs.stream().map(b -> new SensitivityEvaluator(in.withEndDate(b.to()), pc.blockMonths(), b.from())).toList();
        int nb = specs.size();
        List<Callable<Line[]>> blockTasks = new ArrayList<>();
        for (int b = 0; b < nb; b++) {
            final int bi = b;
            blockTasks.add(() -> {
                Line[] l = new Line[nl];
                for (int g = 0; g < nl; g++) {
                    Cell c = cell(blockEvs.get(bi), false, sets, grid.get(g), ref, specs.get(bi).from(), specs.get(bi).to());
                    l[g] = c == null ? null : c.line();
                }
                return l;
            });
        }
        List<Line[]> blockLines = ExposurePolicyStudy.ordered(blockTasks, pool);

        // --- calage ancré à chaque T, pour chaque profil et chaque marge (aucune donnée postérieure à T : worstByLevel + historique clos à T − 1)
        double[][] worstAt = new double[na][];
        int[] calibWindows = new int[na];
        for (Anchor a : anchors) {
            worstAt[a.index()] = worstByLevel(wins, windowDd, fullDd[a.index()], a.day());
            int n = 0;
            for (int w = 0; w < wins.size(); w++) {
                n += wins.get(w).to().isBefore(a.day()) && !Double.isNaN(windowDd[w][0]) ? 1 : 0;
            }
            calibWindows[a.index()] = n;
        }
        ConstantPick[][][] picks = new ConstantPick[np][nk][na];
        Map<Integer, TreeSet<Double>> keys = new TreeMap<>();
        for (int p = 0; p < np; p++) {
            double cap = FrontierConfig.profileCaps().get(profiles.get(p));
            for (int k = 0; k < nk; k++) {
                for (Anchor a : anchors) {
                    if (worstAt[a.index()] == null || a.testTo().isAfter(lastEnd)) {
                        continue;
                    }
                    ConstantPick pk = pick(grid, worstAt[a.index()], cap, cfg.margins().get(k));
                    picks[p][k][a.index()] = pk;
                    keys.computeIfAbsent(a.index(), i -> new TreeSet<>()).add(pk.multiplier());
                }
            }
        }
        List<SensitivityEvaluator> testEvs = new ArrayList<>();
        for (Anchor a : anchors) {
            testEvs.add(new SensitivityEvaluator(in.withEndDate(a.testTo().isAfter(lastEnd) ? lastEnd : a.testTo()), cfg.testMonths(), a.day()));
        }
        List<Callable<Map<Double, Cell>>> testTasks = new ArrayList<>();
        List<Integer> testAnchors = new ArrayList<>(keys.keySet());
        long testReplays = 0;
        for (int ai : testAnchors) {
            TreeSet<Double> ms = keys.get(ai);
            testReplays += ms.size();
            Anchor a = anchors.get(ai);
            testTasks.add(() -> {
                Map<Double, Cell> out = new TreeMap<>();
                for (double m : ms) {
                    out.put(m, cell(testEvs.get(ai), false, sets, m, ref, a.day(), a.testTo()));
                }
                return out;
            });
        }
        List<Map<Double, Cell>> testRaw = ExposurePolicyStudy.ordered(testTasks, pool);
        Map<TestKey, Cell> tests = new HashMap<>();
        for (int i = 0; i < testAnchors.size(); i++) {
            for (Map.Entry<Double, Cell> e : testRaw.get(i).entrySet()) {
                tests.put(new TestKey(testAnchors.get(i), e.getKey()), e.getValue());
            }
        }

        List<List<MarginResult>> marginResults = new ArrayList<>();
        for (int p = 0; p < np; p++) {
            double cap = FrontierConfig.profileCaps().get(profiles.get(p));
            List<MarginResult> byK = new ArrayList<>();
            for (int k = 0; k < nk; k++) {
                List<TestPoint> pts = new ArrayList<>();
                for (Anchor a : anchors) {
                    ConstantPick pk = picks[p][k][a.index()];
                    Cell c = pk == null ? null : tests.get(new TestKey(a.index(), pk.multiplier()));
                    if (c == null || !c.complete()) {
                        continue;
                    }
                    double dd = c.line().maxDrawdownPct();
                    pts.add(new TestPoint(a.day(), a.testTo(), cfg.overlapsBear(a.day(), a.testTo()), pk.multiplier(), pk.status(), calibWindows[a.index()], dd,
                            ratio(dd, cap), holds(dd, cap), c.line().pnlPct(), c.line().avgExposurePct()));
                }
                byK.add(margin(cfg.margins().get(k), cap, pts));
            }
            marginResults.add(byK);
        }
        int anchorsTested = marginResults.getFirst().getFirst().tested();
        int anchorsBear = (int) marginResults.getFirst().getFirst().points().stream().filter(TestPoint::bear).count();

        // --- marge recommandée : plus petit k dont le plafond tient sur ≥ 90 % des T pour TOUS les profils
        double[] minShare = new double[nk];
        List<KSummary> kSummaries = new ArrayList<>();
        for (int k = 0; k < nk; k++) {
            double min = Double.NaN;
            for (int p = 0; p < np; p++) {
                Double s = marginResults.get(p).get(k).holdShare();
                if (s != null && (Double.isNaN(min) || s < min - EPS)) {
                    min = s;
                }
            }
            minShare[k] = min;
        }
        int rec = firstSufficient(minShare, cfg.holdShare());
        for (int k = 0; k < nk; k++) {
            String wp = "n/d";
            for (int p = 0; p < np; p++) {
                Double s = marginResults.get(p).get(k).holdShare();
                if (s != null && !Double.isNaN(minShare[k]) && Math.abs(s - minShare[k]) < EPS) {
                    wp = profiles.get(p).name();
                    break;
                }
            }
            kSummaries.add(new KSummary(cfg.margins().get(k), minShare[k], wp, k == rec));
        }
        Double recommendedK = rec < 0 ? null : cfg.margins().get(rec);
        String kRule = String.format(Locale.ROOT, "k recommandé = plus petit k de %s tel que le plafond tienne sur ≥ %.0f %% des T pour tous les profils (le profil le plus "
                        + "mauvais est nommé) ; aucun k ≤ %s ne suffit => non recommandé, pas d'extrapolation.", cfg.margins(), 100.0 * cfg.holdShare(),
                cfg.margins().getLast());
        double kUse = recommendedK != null ? recommendedK : cfg.margins().getLast();

        // --- calage final (tout le passé) : niveau sans marge, niveau recommandé, niveau de C1 (4 blocs)
        double[] worstFinal = worstByLevel(wins, windowDd, fullDd[na], lastEnd.plusDays(1));
        double[] worstBlocks = new double[nl];
        for (int g = 0; g < nl; g++) {
            for (int b = 0; b < nb; b++) {
                if (blockLines.get(b)[g] != null) {
                    worstBlocks[g] = Math.min(worstBlocks[g], blockLines.get(b)[g].maxDrawdownPct());
                }
            }
        }
        ConstantPick[] noMargin = new ConstantPick[np];
        ConstantPick[] recPick = new ConstantPick[np];
        ConstantPick[] c1Pick = new ConstantPick[np];
        TreeSet<Double> evalKeys = new TreeSet<>();
        for (int p = 0; p < np; p++) {
            double cap = FrontierConfig.profileCaps().get(profiles.get(p));
            noMargin[p] = pick(grid, worstFinal, cap, 1.0);
            recPick[p] = pick(grid, worstFinal, cap, kUse);
            c1Pick[p] = pick(grid, worstBlocks, cap, 1.0);
            evalKeys.add(recPick[p].multiplier());
            evalKeys.add(c1Pick[p].multiplier());
        }
        List<Double> evalList = new ArrayList<>(evalKeys);
        List<Callable<Eval>> evalTasks = new ArrayList<>();
        for (double m : evalList) {
            evalTasks.add(() -> {
                Line[] bl = new Line[nb];
                for (int b = 0; b < nb; b++) {
                    Cell c = cell(blockEvs.get(b), false, sets, m, ref, specs.get(b).from(), specs.get(b).to());
                    bl[b] = c == null ? null : c.line();
                }
                Cell f = cell(fullEv, true, sets, m, ref, null, null);
                return new Eval(bl, f == null ? null : f.line());
            });
        }
        List<Eval> evalRaw = ExposurePolicyStudy.ordered(evalTasks, pool);
        Map<Double, Eval> evals = new TreeMap<>();
        for (int i = 0; i < evalList.size(); i++) {
            evals.put(evalList.get(i), evalRaw.get(i));
        }

        Double meanMult = in.assets().getFirst().meanMultiplier();
        Onboarding onboarding = onboarding(in, meanMult, ref);
        double autMin = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS).min().doubleValue();
        double autMax = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS).max().doubleValue();
        List<ProfileCalibration> results = new ArrayList<>();
        for (int p = 0; p < np; p++) {
            double cap = FrontierConfig.profileCaps().get(profiles.get(p));
            Eval recEval = evals.get(recPick[p].multiplier());
            Eval c1Eval = evals.get(c1Pick[p].multiplier());
            double recGain = medianGain(recEval);
            double c1Gain = medianGain(c1Eval);
            Double recAut = equivalentAutonomy(in.cash0(), meanMult, recPick[p].multiplier(), ref);
            String ww = worstWindow(cfg, wins, windowDd, fullDd[na], crossing(worstFinal, cap, 1.0));
            NoMargin nm = new NoMargin(noMargin[p].multiplier(), noMargin[p].status(), ww, ww.contains(BEAR_TAG));
            C1Level c1 = new C1Level(c1Pick[p].multiplier(), c1Pick[p].status(), exposure(c1Eval), c1Gain,
                    c1Eval.full() == null ? null : c1Eval.full().maxDrawdownPct(), c1Eval.full() != null && holds(c1Eval.full().maxDrawdownPct(), cap),
                    equivalentAutonomy(in.cash0(), meanMult, c1Pick[p].multiplier(), ref));
            Recommendation r = new Recommendation(kUse, recommendedK != null, recPick[p].multiplier(), recPick[p].status(), exposure(recEval),
                    recEval.full() == null ? null : recEval.full().avgExposurePct(), recGain, recGain - c1Gain,
                    recEval.full() == null ? null : recEval.full().maxDrawdownPct(), recEval.full() != null && holds(recEval.full().maxDrawdownPct(), cap),
                    recAut, recAut != null && recAut >= autMin - EPS && recAut <= autMax + EPS);
            results.add(new ProfileCalibration(profiles.get(p).name(), cap, nm, c1, marginResults.get(p), r));
        }

        int valid = (int) Arrays.stream(windowDd).filter(d -> !Double.isNaN(d[0])).count();
        int containing = 0;
        int overlapping = 0;
        for (int w = 0; w < wins.size(); w++) {
            if (Double.isNaN(windowDd[w][0])) {
                continue;
            }
            containing += cfg.containsBear(wins.get(w).from(), wins.get(w).to()) ? 1 : 0;
            overlapping += cfg.overlapsBear(wins.get(w).from(), wins.get(w).to()) ? 1 : 0;
        }
        Windows wsum = new Windows(valid, wins.isEmpty() ? null : wins.getFirst().label(), wins.isEmpty() ? null : wins.getLast().label(), containing,
                overlapping, truncated, notReplayable);
        long replays = fullEv.replays();
        for (SensitivityEvaluator e : winEvs) {
            replays += e.replays();
        }
        for (SensitivityEvaluator e : fullEvs) {
            replays += e == fullEv ? 0 : e.replays();
        }
        for (SensitivityEvaluator e : blockEvs) {
            replays += e.replays();
        }
        for (SensitivityEvaluator e : testEvs) {
            replays += e.replays();
        }
        Multiplicity mult = new Multiplicity(nl, wins.size(), na, nk, np, (long) na * np * nk, testReplays, replays);
        return new AssetCalibration(fullEv.asset(), in.assets().getFirst().source(), in.cash0(), ref, CandleFingerprint.of(fullEv.asset(),
                fullEv.template().candles(), lastEnd), grid, wsum, na, anchorsTested, anchorsBear, results, kSummaries, recommendedK, kRule, onboarding, mult,
                (System.nanoTime() - t0) / 1_000_000);
    }

    private static MarginResult margin(double k, double cap, List<TestPoint> pts) {
        List<Double> all = pts.stream().map(TestPoint::ratio).toList();
        List<TestPoint> bear = pts.stream().filter(TestPoint::bear).toList();
        List<TestPoint> other = pts.stream().filter(t -> !t.bear()).toList();
        int h = (int) pts.stream().filter(TestPoint::holds).count();
        return new MarginResult(k, cap / k, pts, pts.size(), h, pts.isEmpty() ? null : (double) h / pts.size(), ExposureParity.percentile(all, 0.5),
                ExposureParity.percentile(all, 0.9), all.isEmpty() ? null : Collections.max(all), bear.size(),
                (int) bear.stream().filter(TestPoint::holds).count(), bear.isEmpty() ? null : bear.stream().mapToDouble(TestPoint::ratio).max().orElse(0.0),
                other.size(), (int) other.stream().filter(TestPoint::holds).count(),
                other.isEmpty() ? null : other.stream().mapToDouble(TestPoint::ratio).max().orElse(0.0));
    }

    private static double medianGain(Eval e) {
        List<Double> g = new ArrayList<>();
        for (Line l : e.blocks()) {
            if (l != null) {
                g.add(l.pnlPct());
            }
        }
        return g.isEmpty() ? 0.0 : ExposurePolicyStudy.median(g);
    }

    private static double exposure(Eval e) {
        List<Double> x = new ArrayList<>();
        for (Line l : e.blocks()) {
            if (l != null) {
                x.add(l.avgExposurePct());
            }
        }
        Double m = mean(x);
        return m == null ? 0.0 : m;
    }

    private static final String BEAR_TAG = "(contient le bear)";

    /** Plage qui fixe le drawdown de calage au niveau {@code level} (borne haute de l'encadrement) : fenêtre ou historique complet. */
    private static String worstWindow(ExposureCalibrationConfig cfg, List<Window> wins, double[][] windowDd, double[] fullFinal, int level) {
        int l = level < 0 ? fullFinal.length - 1 : level;
        String label = "n/d";
        double min = 0.0;
        for (int w = 0; w < wins.size(); w++) {
            double v = windowDd[w][l];
            if (!Double.isNaN(v) && v < min - EPS) {
                min = v;
                label = "fenêtre " + wins.get(w).label() + (cfg.containsBear(wins.get(w).from(), wins.get(w).to()) ? " " + BEAR_TAG : "");
            }
        }
        return !Double.isNaN(fullFinal[l]) && fullFinal[l] < min - EPS ? "historique complet" : label;
    }

    private static Onboarding onboarding(ScorerInputs in, Double mean, double ref) {
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS);
        int def = limit.defaultValue().intValueExact();
        Double base = mean == null || mean <= 0 ? null : RainbowLiveOnboardingService.referenceBaseAmount(in.cash0(), def, mean).doubleValue();
        String note = "Équivalence exacte sur le MONTANT DE BASE seulement : le ×1 de C1 est la base enregistrée du lien (linkBaseAmount, à défaut base du preset), "
                + "pas la proposition d'onboarding ; autonomie équivalente = cash / (multiplicateur moyen × ×m × base ×1), avec le cash alloué au lien = capital rejoué "
                + "(un wallet à plusieurs liens partage son cash : cash / nombre de liens).";
        return new Onboarding(mean, ref, in.assets().getFirst().referenceBase(), def, base, base == null ? null : base / ref, limit.min().doubleValue(),
                limit.max().doubleValue(), note);
    }
}
