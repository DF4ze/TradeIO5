package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.AssetFrontier;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Estimate;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.ProfileLevel;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Range;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Summary;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Band;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Metrics;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Étape C1 : le preset en place garde-t-il son avantage de timing quand l'exposition monte, et quelle exposition tient le plafond de
 * drawdown de chaque profil ? Un seul levier : le multiplicateur du montant de base du lien (preset, Trend, modulation inchangés). À chaque
 * niveau, même mesure que l'étape B' ({@link ExposureParity} : DCA fixe calé sur la même exposition moyenne, bande de bruit de 20 décalages).
 * Hors ligne : rien n'est appliqué ni persisté. Déterministe (le pool ne change que la durée, l'ordre des résultats est fixe).
 */
public final class ExposureFrontier {

    private static final double EPS = 1e-9;

    private ExposureFrontier() {
    }

    /** Rejeux au pire : plages × niveaux × (preset + calibrage + décalages). */
    public static Estimate estimate(FrontierConfig cfg, int levels) {
        ParityConfig p = cfg.parity();
        long total = (p.blockCount() + 1L) * levels * (1L + p.maxIterations() + p.shiftCount());
        return new Estimate(total, p.replayMs(), total * p.replayMs() / 60_000.0);
    }

    /** Multiplicateur → base du lien imposée ({@code null} pour ×1 : chiffres identiques à l'étape B'). */
    static Double linkBase(double multiplier, double reference) {
        return Math.abs(multiplier - 1.0) < EPS ? null : multiplier * reference;
    }

    private record Ctx(int index, String label, SensitivityEvaluator ev, boolean full) {
    }

    /** Mesure un niveau sur une plage (preset + DCA égal calé + bande), comme {@link ExposureParity#measure}. */
    static Level measureLevel(Ctx c, RainbowAtrParamSet[] sets, double multiplier, double reference, ParityConfig cfg, double cash0,
                              List<Integer> shifts) {
        Double lb = linkBase(multiplier, reference);
        Parity first = c.ev.parity(sets, c.full, 0, null, lb);
        if (first == null) {
            return Level.invalid(multiplier);
        }
        ExposureParity.Calibration cal = ExposureParity.calibrate(c.ev, sets, c.full, first, cash0, cfg.tolerancePts(), cfg.maxIterations(), lb);
        List<Double> gains = new ArrayList<>();
        List<Double> ratios = new ArrayList<>();
        for (int s : shifts) {
            Parity p = s == 0 ? cal.parity() : c.ev.parity(sets, c.full, s, cal.base(), lb);
            gains.add(p == null ? null : p.dca().pnlPct());
            ratios.add(p == null ? null : ExposureParity.gainOverDd(p.dca().pnlPct(), p.dca().maxDrawdownPct()));
        }
        Band band = Band.of(shifts, gains, ratios, cfg.bandPercentile());
        Metrics preset = ExposureParity.metrics(first.preset());
        Metrics equal = ExposureParity.metrics(cal.parity().dca());
        Double dRatio = preset.gainOverDd() == null || equal.gainOverDd() == null ? null : preset.gainOverDd() - equal.gainOverDd();
        boolean gainWin = preset.gainPct() > equal.gainPct() + EPS;
        boolean out = band.gainP80() != null && preset.gainPct() > band.gainP80() + EPS;
        return new Level(multiplier, true, preset, first.realized(), first.latent(), equal, cal.calib(), band, preset.gainPct() - equal.gainPct(),
                preset.maxDrawdownPct() - equal.maxDrawdownPct(), dRatio, gainWin, out, cal.calib().reached());
    }

    private static List<Level> measureLevels(Ctx c, RainbowAtrParamSet[] sets, List<Double> mults, double reference, ParityConfig cfg, double cash0,
                                             List<Integer> shifts, ExecutorService pool) {
        List<Level> out = new ArrayList<>();
        if (pool == null) {
            for (double m : mults) {
                out.add(measureLevel(c, sets, m, reference, cfg, cash0, shifts));
            }
            return out;
        }
        List<Future<Level>> fs = new ArrayList<>();
        for (double m : mults) {
            fs.add(pool.submit(() -> measureLevel(c, sets, m, reference, cfg, cash0, shifts)));
        }
        try {
            for (Future<Level> f : fs) {
                out.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
        return out;
    }

    /** Mesure un actif : grille de base ; si un plafond n'est pas encadré, UNE élargissement de cette seule grille. */
    public static AssetFrontier run(ScorerInputs in, FrontierConfig cfg, ExecutorService pool) {
        long t0 = System.nanoTime();
        ParityConfig pc = cfg.parity();
        double cash0 = in.cash0();
        List<Block> specs = TuneBlocks.build(pc.lastEnd(), pc.blockMonths(), pc.blockCount());
        List<Integer> shifts = ExposureParity.shifts(pc.shiftCount(), pc.shiftMaxDays());
        List<Ctx> ctxs = new ArrayList<>();
        RainbowAtrParamSet[] sets = null;
        SensitivityEvaluator head = null;
        for (Block b : specs) {
            SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(b.to()), pc.blockMonths(), b.from());
            if (sets == null) {
                sets = ev.template().sets();
                head = ev;
            }
            ctxs.add(new Ctx(b.index(), b.label(), ev, false));
        }
        SensitivityEvaluator fullEv = new SensitivityEvaluator(in.withEndDate(pc.lastEnd()), pc.blockMonths());
        ctxs.add(new Ctx(-1, "historique complet", fullEv, true));
        double reference = head.referenceBase(sets);

        List<Double> grid = new ArrayList<>(cfg.multipliers());
        List<List<Level>> levels = new ArrayList<>();
        for (Ctx c : ctxs) {
            levels.add(measureLevels(c, sets, grid, reference, pc, cash0, shifts, pool));
        }
        boolean extended = false;
        List<Double> extra = extension(cfg, grid, levels, specs.size());
        if (!extra.isEmpty()) {
            extended = true;
            for (int i = 0; i < ctxs.size(); i++) {
                List<Level> more = measureLevels(ctxs.get(i), sets, extra, reference, pc, cash0, shifts, pool);
                List<Level> merged = new ArrayList<>(levels.get(i));
                merged.addAll(more);
                merged.sort(Comparator.comparingDouble(Level::multiplier));
                levels.set(i, merged);
            }
            grid.addAll(extra);
            grid.sort(Comparator.naturalOrder());
        }
        List<Range> ranges = new ArrayList<>();
        long replays = 0;
        for (int i = 0; i < ctxs.size(); i++) {
            Ctx c = ctxs.get(i);
            Level any = levels.get(i).stream().filter(Level::valid).findFirst().orElse(null);
            replays += c.ev.replays();
            ranges.add(new Range(c.index, c.label, null, null, any != null, levels.get(i)));
        }
        List<Range> blocks = ranges.subList(0, specs.size());
        Range full = ranges.getLast();
        List<Summary> summaries = summarize(blocks, grid, cfg);
        Double satFrom = summaries.stream().filter(Summary::saturated).map(Summary::multiplier).findFirst().orElse(null);
        Double peak = summaries.stream().filter(s -> s.medianRatio() != null).max(Comparator.comparingDouble(Summary::medianRatio))
                .map(Summary::multiplier).orElse(null);
        List<ProfileLevel> profiles = new ArrayList<>();
        for (Map.Entry<StartingProfile, Double> e : FrontierConfig.profileCaps().entrySet()) {
            profiles.add(profile(e.getKey().name(), e.getValue(), blocks, grid, summaries, pc));
        }
        String[] verdict = verdict(profiles, summaries);
        return new AssetFrontier(head.asset(), in.assets().getFirst().source(), cash0, reference, CandleFingerprint.of(head.asset(),
                head.template().candles(), pc.lastEnd()), grid, extended, new ArrayList<>(blocks), full, summaries, satFrom, peak, profiles,
                verdict[0], verdict[1], replays, (System.nanoTime() - t0) / 1_000_000);
    }

    /** Élargissement unique : haut si un plafond n'est pas atteint et que l'exposition ne sature pas ; bas si le plus bas niveau dépasse un plafond. */
    private static List<Double> extension(FrontierConfig cfg, List<Double> grid, List<List<Level>> levels, int blockCount) {
        List<Range> blocks = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            blocks.add(new Range(i, "", null, null, true, levels.get(i)));
        }
        List<Summary> sums = summarize(blocks, grid, cfg);
        List<Double> extra = new ArrayList<>();
        if (sums.isEmpty()) {
            return extra;
        }
        boolean saturated = sums.stream().anyMatch(Summary::saturated);
        for (double cap : FrontierConfig.profileCaps().values()) {
            if (sums.getLast().worstDrawdown() >= cap && !saturated) {
                cfg.extendUp().stream().filter(m -> !grid.contains(m) && !extra.contains(m)).forEach(extra::add);
            }
            if (sums.getFirst().worstDrawdown() < cap) {
                cfg.extendDown().stream().filter(m -> !grid.contains(m) && !extra.contains(m)).forEach(extra::add);
            }
        }
        return extra;
    }

    private static double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (a.length == 0) {
            return 0.0;
        }
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    private static List<Level> at(List<Range> blocks, double m) {
        List<Level> out = new ArrayList<>();
        for (Range r : blocks) {
            r.levels().stream().filter(l -> Math.abs(l.multiplier() - m) < EPS && l.valid()).findFirst().ifPresent(out::add);
        }
        return out;
    }

    /** Synthèse par niveau sur les blocs rejouables ; règle de niveau de l'étape C1 (fixée avant lecture) dans {@link #passes}. */
    static List<Summary> summarize(List<Range> blocks, List<Double> grid, FrontierConfig cfg) {
        ParityConfig pc = cfg.parity();
        List<Summary> out = new ArrayList<>();
        Summary prev = null;
        for (double m : new TreeSet<>(grid)) {
            List<Level> ls = at(blocks, m);
            if (ls.isEmpty()) {
                continue;
            }
            double expo = ls.stream().mapToDouble(l -> l.preset().exposurePct()).average().orElse(0.0);
            double gainMed = median(ls.stream().map(l -> l.preset().gainPct()).toList());
            double worstDd = ls.stream().mapToDouble(l -> l.preset().maxDrawdownPct()).min().orElse(0.0);
            List<Double> ratios = ls.stream().map(l -> l.preset().gainOverDd()).filter(v -> v != null).toList();
            Double ratioMed = ratios.isEmpty() ? null : median(ratios);
            int won = (int) ls.stream().filter(Level::gainWin).count();
            double dMed = median(ls.stream().map(Level::deltaGain).toList());
            double worstDDd = ls.stream().mapToDouble(Level::deltaDrawdown).min().orElse(0.0);
            boolean concluded = ls.stream().allMatch(Level::dcaReached);
            boolean passes = concluded && passes(won, dMed, worstDDd, pc);
            boolean sat = prev != null && expo - prev.exposurePct() < cfg.saturationElasticity() * prev.exposurePct() * (m / prev.multiplier() - 1.0);
            Double gEl = prev != null && prev.medianGain() > EPS && gainMed > EPS && expo > EPS && prev.exposurePct() > EPS
                    && Math.abs(expo - prev.exposurePct()) > EPS ? Math.log(gainMed / prev.medianGain()) / Math.log(expo / prev.exposurePct()) : null;
            Double dEl = prev != null && prev.worstDrawdown() < -EPS && worstDd < -EPS && expo > EPS && prev.exposurePct() > EPS
                    && Math.abs(expo - prev.exposurePct()) > EPS ? Math.log(worstDd / prev.worstDrawdown()) / Math.log(expo / prev.exposurePct()) : null;
            Summary s = new Summary(m, expo, gainMed, worstDd, ratioMed, won, ls.size(), dMed, worstDDd, concluded, passes, sat, gEl, dEl);
            out.add(s);
            prev = s;
        }
        return out;
    }

    /**
     * Règle de verdict (fixée avant lecture) : au moins {@code minBlocksPass} blocs où le preset bat le DCA égal en gain, médiane du Δ gain &gt; 0
     * et aucun bloc où le drawdown du preset est pire que celui du DCA égal de plus de {@code drawdownMargin} pts.
     */
    static boolean passes(int blocksWon, double medianDeltaGain, double worstDeltaDrawdown, ParityConfig pc) {
        return blocksWon >= pc.minBlocksPass() && medianDeltaGain > 0 && worstDeltaDrawdown >= -pc.drawdownMargin();
    }

    private static double lerp(double a, double b, double w) {
        return a + (b - a) * w;
    }

    /**
     * Exposition maximale dont le pire drawdown sur les blocs respecte {@code cap} : premier franchissement en montant la grille, interpolé
     * linéairement (en pire drawdown) entre les deux points de grille qui l'encadrent ; gains, Δ gain et Δ drawdown de chaque bloc
     * interpolés avec le même poids.
     */
    static ProfileLevel profile(String name, double cap, List<Range> blocks, List<Double> grid, List<Summary> sums, ParityConfig pc) {
        int k = -1;
        for (int i = 0; i < sums.size(); i++) {
            if (sums.get(i).worstDrawdown() < cap) {
                k = i;
                break;
            }
        }
        if (k == 0) {
            return new ProfileLevel(name, cap, "SOUS_LE_MINIMUM", null, null, null, null, 0, 0, 0, "NON_CONCLU",
                    "le plus bas niveau de la grille dépasse déjà le plafond");
        }
        if (k < 0) {
            Summary top = sums.getLast();
            String st = top.saturated() ? "PLAFOND_NON_ATTEINT_SATURE" : "PLAFOND_NON_ATTEINT";
            return new ProfileLevel(name, cap, st, top.multiplier(), top.exposurePct(), top.medianGain(), top.worstDrawdown(), top.blocksWon(),
                    top.medianDeltaGain(), top.worstDeltaDrawdown(), !top.concluded() ? "NON_CONCLU" : top.passes() ? "AVANTAGE_PERSISTANT" : "S_EFFONDRE",
                    "plafond non atteint au plus haut niveau de la grille : valeur de ce point de grille, aucune interpolation");
        }
        Summary lo = sums.get(k - 1);
        Summary hi = sums.get(k);
        double w = (cap - lo.worstDrawdown()) / (hi.worstDrawdown() - lo.worstDrawdown());
        List<Level> la = at(blocks, lo.multiplier());
        List<Level> lb = at(blocks, hi.multiplier());
        boolean concluded = lo.concluded() && hi.concluded() && la.size() == lb.size();
        List<Double> gains = new ArrayList<>();
        List<Double> dg = new ArrayList<>();
        double worstDDd = Double.MAX_VALUE;
        int won = 0;
        for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
            double g = lerp(la.get(i).preset().gainPct(), lb.get(i).preset().gainPct(), w);
            double d = lerp(la.get(i).deltaGain(), lb.get(i).deltaGain(), w);
            gains.add(g);
            dg.add(d);
            won += d > EPS ? 1 : 0;
            worstDDd = Math.min(worstDDd, lerp(la.get(i).deltaDrawdown(), lb.get(i).deltaDrawdown(), w));
        }
        double dMed = median(dg);
        double mult = lerp(lo.multiplier(), hi.multiplier(), w);
        String verdict = !concluded ? "NON_CONCLU" : passes(won, dMed, worstDDd, pc) ? "AVANTAGE_PERSISTANT" : "S_EFFONDRE";
        return new ProfileLevel(name, cap, "INTERPOLE", mult, lerp(lo.exposurePct(), hi.exposurePct(), w), median(gains), cap, won, dMed, worstDDd,
                verdict, String.format(java.util.Locale.ROOT, "interpolé entre ×%s et ×%s (poids %.2f)", lo.multiplier(), hi.multiplier(), w));
    }

    /** Verdict de l'actif : avantage persistant si les quatre profils le sont ; sinon profils en échec et exposition de l'effondrement. */
    static String[] verdict(List<ProfileLevel> profiles, List<Summary> sums) {
        boolean all = profiles.stream().allMatch(p -> "AVANTAGE_PERSISTANT".equals(p.verdict()));
        if (all) {
            return new String[]{"AVANTAGE_PERSISTANT", "Le preset bat le DCA égal à l'exposition de chacun des 4 profils (règle fixée)."};
        }
        List<String> bad = profiles.stream().filter(p -> "S_EFFONDRE".equals(p.verdict())).map(ProfileLevel::profile).toList();
        List<String> unk = profiles.stream().filter(p -> "NON_CONCLU".equals(p.verdict())).map(ProfileLevel::profile).toList();
        Summary first = sums.stream().filter(s -> s.concluded() && !s.passes()).findFirst().orElse(null);
        StringBuilder sb = new StringBuilder();
        if (!bad.isEmpty()) {
            sb.append("S'effondre pour : ").append(bad).append(". ");
        }
        if (!unk.isEmpty()) {
            sb.append("Non conclu (DCA inatteignable ou plafond hors grille) : ").append(unk).append(". ");
        }
        sb.append(first == null ? "Aucun niveau de grille conclu ne viole la règle."
                : String.format(java.util.Locale.ROOT, "Premier niveau de grille hors règle : ×%s (exposition moyenne %.2f %%).", first.multiplier(),
                first.exposurePct()));
        return new String[]{bad.isEmpty() ? "NON_CONCLU" : "NON_PERSISTANT", sb.toString()};
    }
}
