package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.TrendMixKpi.Front;
import fr.ses10doigts.tradeIO5.service.dca.atr.TrendMixKpi.Kpis;
import fr.ses10doigts.tradeIO5.service.dca.atr.TrendMixKpi.Rainbow;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator.Params;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/**
 * Harnais de mesure des KPI du Trend Mix (lot 1 de {@code docs/etudes/etude-bench-auto-trend-mix-nouvel-actif.md}) :
 * AUCUNE optimisation, aucune grille, aucune écriture en production. Mesure le défaut {@link Params#defaults()}, ses
 * perturbations d'un cran et des baselines (Bull seul, Bear seul, Perso Generic fixe, Trend aléatoire).
 * <p>
 * Usage (racine du repo) : {@code TrendMixBenchMain <dir> [BTC,ETH,PAXG]}. Entrée {@code <dir>/d1_<ACTIF>.csv}
 * ({@code t_ms,open,high,low,close}, D1 UTC, historique complet, Binance). Sorties dans {@code <dir>/<ACTIF>/} :
 * {@code rapport.md}, {@code kpi_candidats.csv}, {@code regimes_defaut.csv}.
 */
public final class TrendMixBenchMain {

    static final String REPLAY_START = "2024-06-10";
    static final int MONTE_CARLO = 200;
    static final long SEED = 20261009L;
    /** Alertes de pouvoir discriminant (étude §1.5). */
    static final double MIN_PASS = 0.10;
    static final double MAX_PASS = 0.90;

    // Crans de l'étude §2.
    static final int[][] WINDOWS = {{7, 14, 30}, {14, 30, 60}, {21, 45, 90}, {30, 60, 120}};
    static final double[] ENTERS = {0.20, 0.25, 0.30, 0.35, 0.40, 0.45};
    static final int[] CONFIRMS = {5, 7, 10, 14, 20};
    static final int[] SMAS = {50, 75, 100, 150, 200};
    static final double[] KS = {0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5};
    static final int[] ATRS = {5, 10, 14};
    /** EXIT = ENTER / 3 (couplé, étude §2 : 0,10 pour 0,30). */
    static final double EXIT_RATIO = 1.0 / 3;

    // Avertissements K17 / K18 (étude §1.4).
    static final double MIN_ATR_PCT = 0.015;
    static final int MIN_SWING_PIVOTS = 8;
    static final double PEG_BAND = 0.03;
    static final double PEG_SHARE = 0.95;
    static final int MIN_DAYS = 3 * 365;
    static final int MIN_MAJOR_PIVOTS = 6;

    record Candidate(String label, Params params) {
    }

    /** Une ligne de mesure ; {@code rb} nul sans jeux Bull/Bear pour l'actif ; {@code stability} = K6 vs défaut. */
    record Row(String label, Kpis k, Rainbow rb, double stability) {
    }

    interface Threshold {
        double at(double def, double sigma);
    }

    record Metric(String name, ToDoubleFunction<Row> value, boolean lowerBetter, Threshold threshold, String rule) {
    }

    private TrendMixBenchMain() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/trend-mix-bench");
        String[] assets = (args.length > 1 ? args[1] : "BTC,ETH,PAXG").split(",");
        for (String asset : assets) {
            run(dir, asset.trim());
        }
    }

    // ------------------------------------------------------------------ candidats

    static List<Candidate> candidates(Params d) {
        List<Candidate> out = new ArrayList<>();
        out.add(new Candidate("défaut", d));
        int w = index(WINDOWS, d);
        for (int j : new int[]{w - 1, w + 1}) {
            if (j >= 0 && j < WINDOWS.length) {
                Params p = new Params(WINDOWS[j][0], WINDOWS[j][1], WINDOWS[j][2], d.slopeScale(), d.enter(), d.exit(),
                        d.confirm(), d.smaPeriod(), d.atrPeriod(), d.atrMultiplier(), d.wickDown(), d.wickUp());
                out.add(new Candidate("fenêtres " + WINDOWS[j][0] + "/" + WINDOWS[j][1] + "/" + WINDOWS[j][2], p));
            }
        }
        int e = nearest(ENTERS, d.enter());
        for (int j : new int[]{e - 1, e + 1}) {
            if (j >= 0 && j < ENTERS.length) {
                Params p = new Params(d.shortWindow(), d.mediumWindow(), d.longWindow(), d.slopeScale(), ENTERS[j],
                        ENTERS[j] * EXIT_RATIO, d.confirm(), d.smaPeriod(), d.atrPeriod(), d.atrMultiplier(), d.wickDown(), d.wickUp());
                out.add(new Candidate(String.format(Locale.ROOT, "ENTER %.2f (EXIT %.3f)", ENTERS[j], ENTERS[j] * EXIT_RATIO), p));
            }
        }
        int c = nearest(CONFIRMS, d.confirm());
        for (int j : new int[]{c - 1, c + 1}) {
            if (j >= 0 && j < CONFIRMS.length) {
                out.add(new Candidate("confirm " + CONFIRMS[j], new Params(d.shortWindow(), d.mediumWindow(), d.longWindow(),
                        d.slopeScale(), d.enter(), d.exit(), CONFIRMS[j], d.smaPeriod(), d.atrPeriod(), d.atrMultiplier(),
                        d.wickDown(), d.wickUp())));
            }
        }
        int s = nearest(SMAS, d.smaPeriod());
        for (int j : new int[]{s - 1, s + 1}) {
            if (j >= 0 && j < SMAS.length) {
                out.add(new Candidate("SMA " + SMAS[j], new Params(d.shortWindow(), d.mediumWindow(), d.longWindow(),
                        d.slopeScale(), d.enter(), d.exit(), d.confirm(), SMAS[j], d.atrPeriod(), d.atrMultiplier(),
                        d.wickDown(), d.wickUp())));
            }
        }
        int k = nearest(KS, d.atrMultiplier());
        for (int j : new int[]{k - 1, k + 1}) {
            if (j >= 0 && j < KS.length) {
                out.add(new Candidate("k " + KS[j], new Params(d.shortWindow(), d.mediumWindow(), d.longWindow(),
                        d.slopeScale(), d.enter(), d.exit(), d.confirm(), d.smaPeriod(), d.atrPeriod(), KS[j],
                        d.wickDown(), d.wickUp())));
            }
        }
        int a = nearest(ATRS, d.atrPeriod());
        for (int j : new int[]{a - 1, a + 1}) {
            if (j >= 0 && j < ATRS.length) {
                out.add(new Candidate("ATR " + ATRS[j], new Params(d.shortWindow(), d.mediumWindow(), d.longWindow(),
                        d.slopeScale(), d.enter(), d.exit(), d.confirm(), d.smaPeriod(), ATRS[j], d.atrMultiplier(),
                        d.wickDown(), d.wickUp())));
            }
        }
        return out;
    }

    private static int index(int[][] grid, Params d) {
        for (int i = 0; i < grid.length; i++) {
            if (grid[i][0] == d.shortWindow() && grid[i][1] == d.mediumWindow() && grid[i][2] == d.longWindow()) {
                return i;
            }
        }
        throw new IllegalArgumentException("fenêtres du défaut hors grille");
    }

    private static int nearest(double[] grid, double v) {
        int best = 0;
        for (int i = 1; i < grid.length; i++) {
            if (Math.abs(grid[i] - v) < Math.abs(grid[best] - v)) {
                best = i;
            }
        }
        return best;
    }

    private static int nearest(int[] grid, int v) {
        int best = 0;
        for (int i = 1; i < grid.length; i++) {
            if (Math.abs(grid[i] - v) < Math.abs(grid[best] - v)) {
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ mesure

    static void run(Path dir, String asset) throws IOException {
        List<MarketData> candles = RainbowAtrTrendBenchMain.load(dir.resolve("d1_" + asset + ".csv"), asset);
        int n = candles.size();
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        double[] close = candles.stream().mapToDouble(m -> m.getClose().doubleValue()).toArray();

        double medAtr = ZigZag.medianAtrPct(ds);
        List<int[]> swing = ZigZag.pivots(close, 0, n - 1, ZigZag.C_SWING * medAtr);
        List<int[]> major = ZigZag.pivots(close, 0, n - 1, ZigZag.C_MAJOR * medAtr);

        List<Candidate> cands = candidates(Params.defaults());
        int from = cands.stream().mapToInt(c -> c.params().warmup()).max().orElseThrow() - 1;
        int to = n - 1;
        RainbowAtrParamSet[] sets = hasPresets(asset) ? RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]) : null;
        double base = RainbowAtrPresets.generic().globals().baseAmount();

        List<Row> rows = new ArrayList<>();
        TrendMixCalculator.Result defResult = null;
        for (Candidate c : cands) {
            Params p = c.params();
            TrendMixCalculator.Result r = TrendMixCalculator.compute(candles, ds.sma(p.smaPeriod()), ds.atr(p.atrPeriod()), p);
            if (defResult == null) {
                defResult = r;
            }
            Kpis k = TrendMixKpi.compute(close, r.regime(), r.source(), swing, major, from, to);
            Rainbow rb = sets == null ? null : TrendMixKpi.rainbow(
                    RainbowAtrReplay.run(ds, sets, RainbowSetSelector.select(r.regime(), RangeMapping.KEEP_PREVIOUS), from, to), base);
            double stab = rows.isEmpty() ? 1.0 : TrendMixKpi.frontStability(rows.getFirst().k(), k);
            rows.add(new Row(c.label(), k, rb, stab));
        }
        Row def = rows.getFirst();
        List<Row> perturbations = rows.subList(1, rows.size());

        Kpis regOnly = TrendMixKpi.compute(close, defResult.regression(), null, swing, major, from, to);
        Kpis smaOnly = TrendMixKpi.compute(close, defResult.sma(), null, swing, major, from, to);

        // Baselines
        Rainbow bull = null;
        Rainbow bear = null;
        Rainbow generic = null;
        List<Row> random = new ArrayList<>();
        if (sets != null) {
            bull = TrendMixKpi.rainbow(RainbowAtrReplay.run(ds, sets, RainbowSetSelector.fixed(n, RainbowAtrPresets.BULL), from, to), base);
            bear = TrendMixKpi.rainbow(RainbowAtrReplay.run(ds, sets, RainbowSetSelector.fixed(n, RainbowAtrPresets.BEAR), from, to), base);
            generic = TrendMixKpi.rainbow(RainbowAtrReplay.run(ds, new RainbowAtrParamSet[]{RainbowAtrPresets.generic()},
                    RainbowSetSelector.fixed(n, 0), from, to), base);
        }
        double switchProb = (double) def.k().switches() / (to - from + 1);
        Random rnd = new Random(SEED);
        for (int m = 0; m < MONTE_CARLO; m++) {
            TrendRegime[] reg = new TrendRegime[n];
            boolean up = rnd.nextBoolean();
            for (int i = from; i <= to; i++) {
                if (rnd.nextDouble() < switchProb) {
                    up = !up;
                }
                reg[i] = up ? TrendRegime.UP : TrendRegime.DOWN;
            }
            Kpis k = TrendMixKpi.compute(close, reg, null, swing, major, from, to);
            Rainbow rb = sets == null ? null : TrendMixKpi.rainbow(
                    RainbowAtrReplay.run(ds, sets, RainbowSetSelector.select(reg, RangeMapping.KEEP_PREVIOUS), from, to), base);
            random.add(new Row("aléatoire " + m, k, rb, Double.NaN));
        }

        Path out = Files.createDirectories(dir.resolve(asset));
        List<String> warnings = warnings(asset, ds, close, medAtr, swing, major, n);
        Files.writeString(out.resolve("rapport.md"), report(asset, ds, rows, def, perturbations, regOnly, smaOnly, bull, bear,
                generic, random, warnings, swing, major, medAtr, from, to, switchProb));
        Files.writeString(out.resolve("kpi_candidats.csv"), kpiCsv(cands, rows));
        Files.writeString(out.resolve("regimes_defaut.csv"), regimesCsv(ds, close, defResult, swing, major, from));
        System.out.printf(Locale.ROOT, "%s : %d candidats, %d switchs défaut, K1=%s, rapport -> %s%n", asset, rows.size(),
                def.k().switches(), f(def.k().nervosity(), 2), out.resolve("rapport.md"));
        warnings.forEach(w -> System.out.println("  ⚠ " + w));
    }

    private static boolean hasPresets(String asset) {
        try {
            RainbowAtrPresets.bearBull(asset);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** K17 (tradabilité) et K18 (historique minimal) : affichés en tête du rapport, jamais un refus. */
    static List<String> warnings(String asset, RainbowAtrDataset ds, double[] close, double medAtr, List<int[]> swing,
                                 List<int[]> major, int n) {
        List<String> w = new ArrayList<>();
        if (medAtr < MIN_ATR_PCT) {
            w.add(String.format(Locale.ROOT, "K17 : ATR%% médian %.2f %% < %.1f %% (volatilité trop faible pour une Trend utile).",
                    medAtr * 100, MIN_ATR_PCT * 100));
        }
        if (swing.size() < MIN_SWING_PIVOTS) {
            w.add("K17 : seulement " + swing.size() + " pivots ZigZag swing (< " + MIN_SWING_PIVOTS + ").");
        }
        double[] s = close.clone();
        Arrays.sort(s);
        double median = s[s.length / 2];
        long near = Arrays.stream(close).filter(c -> Math.abs(c / median - 1) <= PEG_BAND).count();
        if ((double) near / close.length >= PEG_SHARE) {
            w.add(String.format(Locale.ROOT, "K17 : %.0f %% des clôtures à ± %.0f %% de la médiane (actif adossé / stablecoin ?).",
                    100.0 * near / close.length, PEG_BAND * 100));
        }
        if (n < MIN_DAYS) {
            w.add("K18 : historique de " + n + " jours D1 (< " + MIN_DAYS + ", soit 3 ans) : échantillon insuffisant.");
        }
        if (major.size() < MIN_MAJOR_PIVOTS) {
            w.add("K18 : " + major.size() + " pivots majeurs (< " + MIN_MAJOR_PIVOTS + ", soit 3 cycles) : échantillon insuffisant.");
        }
        if (!hasPresets(asset)) {
            w.add("Pas de jeux Bull/Bear pour " + asset + " : K8-K11 et baselines Rainbow non calculés.");
        }
        return w;
    }

    // ------------------------------------------------------------------ pouvoir discriminant

    static List<Metric> metrics() {
        return List.of(
                new Metric("K1 écart à la zone [1 ; 2,5]", r -> {
                    double v = r.k().nervosity();
                    return Double.isNaN(v) ? Double.NaN : Math.max(0, Math.max(1.0 - v, v - 2.5));
                }, true, (d, s) -> 0.0, "= 0 (K1 dans [1 ; 2,5])"),
                new Metric("K2 durée médiane (j)", r -> r.k().medianSegment(), false, (d, s) -> 0.5 * d, "≥ 0,5 × défaut"),
                new Metric("K3 jours flip-flop Bear en Bull", r -> r.k().bearInBullDays(), true, (d, s) -> d + 2 * s, "≤ défaut + 2σ (provisoire)"),
                new Metric("K4 jours flip-flop Bull en Bear ×2", r -> r.k().bullInBearWeightedDays(), true, (d, s) -> d + 2 * s, "≤ défaut + 2σ (provisoire)"),
                new Metric("K5a part de baisse subie (ATH ×2)", r -> r.k().topShareWeighted(), true, (d, s) -> d + 2 * s, "≤ défaut + 2σ (provisoire)"),
                new Metric("K5b part de hausse passée", r -> r.k().bottomShare(), true, (d, s) -> d + 3 * s, "≤ défaut + 3σ (provisoire, plus tolérant)"),
                new Metric("K6 stabilité des fronts", Row::stability, false, (d, s) -> 0.8, "≥ 80 %"),
                new Metric("K7b contre-sens pondéré 2:1", r -> r.k().counterTrendWeighted(), true, (d, s) -> d, "≤ défaut"),
                new Metric("K8 excès Rainbow vs DCA fixe (%)", r -> r.rb() == null ? Double.NaN : r.rb().excessPct(), false, (d, s) -> d - 2 * s, "≥ défaut − 2σ"),
                new Metric("K9 pic d'exposition (% budget)", r -> r.rb() == null ? Double.NaN : r.rb().peakPct(), true, (d, s) -> 1.2 * d, "≤ 1,2 × défaut"),
                new Metric("K10 drawdown (% budget)", r -> r.rb() == null ? Double.NaN : r.rb().drawdownPct(), true, (d, s) -> d, "≤ défaut"),
                new Metric("K11 ventes/rachats par an (min)", r -> r.rb() == null ? Double.NaN : r.rb().minEventsPerYear(), false, (d, s) -> 1.0, "≥ 1 par an"));
    }

    private static boolean passes(Metric m, double v, double thr) {
        return !Double.isNaN(v) && (m.lowerBetter() ? v <= thr + 1e-12 : v >= thr - 1e-12);
    }

    // ------------------------------------------------------------------ rapport

    @SuppressWarnings("java:S107")
    private static String report(String asset, RainbowAtrDataset ds, List<Row> rows, Row def, List<Row> perts, Kpis regOnly,
                                 Kpis smaOnly, Rainbow bull, Rainbow bear, Rainbow generic, List<Row> random,
                                 List<String> warnings, List<int[]> swing, List<int[]> major, double medAtr, int from, int to,
                                 double switchProb) {
        StringBuilder sb = new StringBuilder();
        Kpis dk = def.k();
        sb.append("# Bench Trend Mix — ").append(asset).append(" (lot 1, mesure sans optimisation)\n\n");
        sb.append("Fenêtre mesurée : ").append(date(ds, from)).append(" → ").append(date(ds, to)).append(" (")
                .append(to - from + 1).append(" j, ").append(f(dk.years(), 1)).append(" ans). Défaut = `Params.defaults()`.\n\n");
        sb.append("## Avertissements K17 / K18\n\n");
        if (warnings.isEmpty()) {
            sb.append("Aucun.\n\n");
        } else {
            warnings.forEach(w -> sb.append("- ⚠ ").append(w).append('\n'));
            sb.append('\n');
        }
        sb.append("## Repères de marché\n\n");
        sb.append("ATR% médian (14 j) = ").append(f(medAtr * 100, 2)).append(" %. ZigZag swing : seuil ")
                .append(f(ZigZag.C_SWING * medAtr * 100, 1)).append(" % (c = ").append(ZigZag.C_SWING).append("), ")
                .append(swing.size()).append(" pivots ; majeur : seuil ").append(f(ZigZag.C_MAJOR * medAtr * 100, 1))
                .append(" % (c = ").append(ZigZag.C_MAJOR).append("), ").append(major.size()).append(" pivots.\n\n");

        // 1. défaut
        sb.append("## 1. KPI du défaut\n\n| KPI | Valeur |\n|---|---|\n");
        sb.append("| K1 nervosité (switchs / pivots swing) | ").append(dk.switches()).append(" / ").append(dk.swingPivots())
                .append(" = ").append(f(dk.nervosity(), 2)).append(" (zone [1 ; 2,5]) |\n");
        sb.append("| K2 durée médiane de régime | ").append(f(dk.medianSegment(), 0)).append(" j |\n");
        sb.append("| K3 flip-flop Bear en Bull | ").append(dk.bearInBull().size()).append(" (").append(f(dk.bearInBullPerYear(), 2))
                .append("/an), ").append(f(dk.bearInBullDays(), 0)).append(" j cumulés |\n");
        sb.append("| K4 flip-flop Bull en Bear (×2) | ").append(dk.bullInBear().size()).append(" (").append(f(dk.bullInBearPerYear(), 2))
                .append("/an), ").append(f(dk.bullInBearDays(), 0)).append(" j cumulés (pondéré ").append(f(dk.bullInBearWeightedDays(), 0)).append(") |\n");
        sb.append("| K5a retard au sommet | ATH : ").append(f(dk.topLag(true), 1)).append(" j, ").append(f(dk.topShare(true) * 100, 0))
                .append(" % de la baisse subie ; autres : ").append(f(dk.topLag(false), 1)).append(" j, ")
                .append(f(dk.topShare(false) * 100, 0)).append(" % ; pondéré ATH×2 : ").append(f(dk.topShareWeighted() * 100, 1)).append(" % |\n");
        sb.append("| K5b retard au creux | ").append(f(dk.bottomLag(), 1)).append(" j, ").append(f(dk.bottomShare() * 100, 1))
                .append(" % de la hausse déjà passée |\n");
        sb.append("| K6 invariance des fronts | voir §2 (stabilité par perturbation) : moyenne ")
                .append(f(perts.stream().mapToDouble(Row::stability).average().orElse(Double.NaN) * 100, 0)).append(" % de fronts stables (±")
                .append(TrendMixKpi.STABLE_DAYS).append(" j) |\n");
        sb.append("| K7 accord équilibré (info) | ").append(f(dk.accord(), 2)).append(" |\n");
        sb.append("| K7b contre-sens | Bull en baisse majeure ").append(f(dk.bullInDownDays(), 0)).append(" j, Bear en hausse majeure ")
                .append(f(dk.bearInUpDays(), 0)).append(" j sur ").append(f(dk.legDays(), 0)).append(" j de jambes ; pondéré 2:1 = ")
                .append(f(dk.counterTrendWeighted(), 3)).append(" |\n");
        if (def.rb() != null) {
            Rainbow r = def.rb();
            sb.append("| K8 Rainbow piloté (excès vs DCA fixe) | ").append(f(r.excessPct(), 1)).append(" % du budget fixe (gain total ").append(f(r.totalGain(), 0))
                    .append(", à budget égal ").append(f(r.scaledGain(), 0)).append(", DCA fixe ").append(f(r.fixedGain(), 0))
                    .append(", capital déployé ").append(f(r.deployedPct(), 0)).append(" %) |\n");
            sb.append("| K9 pic d'exposition | ").append(f(r.peakPct(), 0)).append(" % du budget fixe |\n");
            sb.append("| K10 drawdown du gain total | ").append(f(r.drawdownPct(), 1)).append(" % du budget fixe |\n");
            sb.append("| K11 ventes / rachats | ").append(r.sells()).append(" / ").append(r.rebuys()).append(" sur ").append(f(r.years(), 1)).append(" ans |\n");
        }
        sb.append('\n');

        // 2. perturbations
        List<Metric> metrics = metrics();
        sb.append("## 2. Perturbations d'un cran (un paramètre à la fois ; ENTER entraîne EXIT = ENTER/3)\n\n");
        sb.append("| candidat | switchs | K1 | K2 | K3 j | K4 j×2 | K5a % | K5b % | K6 stable | K7b | K8 excès % | K9 % | K10 % | ventes/rachats |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append(rowLine(r));
        }
        double[] sigma = new double[metrics.size()];
        sb.append("\n**σ (écart-type des perturbations) et marge de proposition = 2σ**\n\n| KPI | défaut | σ | marge 2σ |\n|---|---|---|---|\n");
        for (int i = 0; i < metrics.size(); i++) {
            Metric m = metrics.get(i);
            sigma[i] = std(perts.stream().mapToDouble(m.value()).toArray());
            sb.append("| ").append(m.name()).append(" | ").append(f(m.value().applyAsDouble(def), 3)).append(" | ")
                    .append(f(sigma[i], 3)).append(" | ").append(f(2 * sigma[i], 3)).append(" |\n");
        }

        // 3. pouvoir discriminant
        sb.append("\n## 3. Pouvoir discriminant (part des ").append(perts.size()).append(" perturbations qui passent chaque veto)\n\n");
        sb.append("Seuils « provisoires » = propositions du harnais, **non figés** (à valider par Clem). Alerte si < 10 % ou > 90 %.\n\n");
        sb.append("| veto | règle | seuil | défaut passe | perturbations qui passent | alerte | seuil recalé proposé (voisinage, ≈ 80 % passent) |\n|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < metrics.size(); i++) {
            Metric m = metrics.get(i);
            double d = m.value().applyAsDouble(def);
            double thr = m.threshold().at(d, sigma[i]);
            double[] vals = perts.stream().mapToDouble(m.value()).filter(v -> !Double.isNaN(v)).sorted().toArray();
            if (vals.length == 0) {
                sb.append("| ").append(m.name()).append(" | ").append(m.rule()).append(" | n/a | n/a | n/a | | |\n");
                continue;
            }
            int pass = 0;
            for (Row p : perts) {
                if (passes(m, m.value().applyAsDouble(p), thr)) {
                    pass++;
                }
            }
            double rate = (double) pass / perts.size();
            boolean alert = rate < MIN_PASS || rate > MAX_PASS;
            double proposed = m.lowerBetter() ? vals[(int) Math.ceil(0.8 * vals.length) - 1] : vals[(int) Math.floor(0.2 * vals.length)];
            sb.append("| ").append(m.name()).append(" | ").append(m.rule()).append(" | ").append(f(thr, 3)).append(" | ")
                    .append(m.name().startsWith("K6") || passes(m, d, thr) ? "oui" : "NON").append(" | ")
                    .append(pass).append("/").append(perts.size()).append(" (").append(f(rate * 100, 0)).append(" %) | ")
                    .append(alert ? "⚠ NON DISCRIMINANT" : "").append(" | ").append(alert ? f(proposed, 3) : "—").append(" |\n");
        }

        // 4. baselines
        sb.append("\n## 4. Baselines (Rainbow `").append(asset).append(" Perso Bear/Bull` fixe sauf mention)\n\n");
        if (def.rb() == null) {
            sb.append("Non calculées (pas de jeux Bull/Bear pour cet actif).\n");
        } else {
            double[] rEx = random.stream().mapToDouble(r -> r.rb().excessPct()).sorted().toArray();
            double[] rGain = random.stream().mapToDouble(r -> r.rb().totalGain()).sorted().toArray();
            sb.append("| stratégie | gain total | gain à budget égal | excès vs DCA fixe % | capital déployé % | pic expo % | drawdown % | ventes / rachats |\n|---|---|---|---|---|---|---|---|\n");
            sb.append(baseLine("Trend Mix défaut", def.rb())).append(baseLine("Bull seul", bull)).append(baseLine("Bear seul", bear))
                    .append(baseLine("Perso Generic fixe (lot 0, sans Trend)", generic));
            sb.append(String.format(Locale.ROOT, "| Trend aléatoire (moy. de %d, p=%.4f/j) | %.0f | %.0f | %.1f | %.0f | %.0f | %.1f | %.1f / %.1f |\n",
                    MONTE_CARLO, switchProb, avg(random, r -> r.rb().totalGain()), avg(random, r -> r.rb().scaledGain()), avg(random, r -> r.rb().excessPct()),
                    avg(random, r -> r.rb().deployedPct()), avg(random, r -> r.rb().peakPct()), avg(random, r -> r.rb().drawdownPct()),
                    avg(random, r -> r.rb().sells()), avg(random, r -> r.rb().rebuys())));
            sb.append(String.format(Locale.ROOT, "%nAléatoire : excès P10 / P50 / P90 = %.1f / %.1f / %.1f %% ; gain total P90 = %.0f.%n%n",
                    pct(rEx, 0.10), pct(rEx, 0.50), pct(rEx, 0.90), pct(rGain, 0.90)));
            sb.append("- Défaut bat Perso Generic (excès) : **").append(def.rb().excessPct() > generic.excessPct() ? "oui" : "NON — la Trend n'apporte rien ici")
                    .append("** (").append(f(def.rb().excessPct(), 1)).append(" vs ").append(f(generic.excessPct(), 1)).append(").\n");
            sb.append("- ").append(genericReplayLine(ds)).append('\n');
            sb.append("- Défaut au-dessus du 90e centile de l'aléatoire : **").append(def.rb().excessPct() > pct(rEx, 0.90) ? "oui" : "NON").append("**.\n");
        }
        double[] rn = random.stream().mapToDouble(r -> r.k().nervosity()).sorted().toArray();
        double[] rTop = random.stream().mapToDouble(r -> r.k().topShareWeighted()).sorted().toArray();
        double[] rCt = random.stream().mapToDouble(r -> r.k().counterTrendWeighted()).sorted().toArray();
        double[] rAcc = random.stream().mapToDouble(r -> r.k().accord()).sorted().toArray();
        sb.append(String.format(Locale.ROOT, "%n**Séparation Trend aléatoire / défaut** (P10 – P90 de l'aléatoire ; défaut) : K1 %.2f – %.2f ; %.2f · K5a %.2f – %.2f ; %.2f · K7b %.3f – %.3f ; %.3f · K7 accord %.2f – %.2f ; %.2f.%n",
                pct(rn, 0.1), pct(rn, 0.9), dk.nervosity(), pct(rTop, 0.1), pct(rTop, 0.9), dk.topShareWeighted(),
                pct(rCt, 0.1), pct(rCt, 0.9), dk.counterTrendWeighted(), pct(rAcc, 0.1), pct(rAcc, 0.9), dk.accord()));

        // 5. ablation
        sb.append("\n## 5. Ablation K19 et bascules SMA K20 (défaut)\n\n");
        sb.append("| composant | switchs | K1 | K2 | K3 j | K4 j×2 | K5a % | K5b % | K7 | K7b |\n|---|---|---|---|---|---|---|---|---|---|\n");
        sb.append(ablLine("Régression seule", regOnly)).append(ablLine("SMA seule", smaOnly)).append(ablLine("Mix", dk));
        sb.append(String.format(Locale.ROOT, "%nK20 : %d switchs du Mix dus à la SMA (%s des %d), dont %d à contretemps (%s des switchs SMA).%n",
                dk.smaSwitches(), f(dk.smaSwitchShare() * 100, 0) + " %", dk.switches(), dk.smaMistimed(),
                f(dk.smaMistimedShare() * 100, 0) + " %"));
        if (dk.smaMistimedShare() > 0.5) {
            sb.append("⚠ plus de 50 % des bascules SMA tombent à contretemps.\n");
        }

        // 6. exemples chiffrés
        sb.append("\n## 6. Exemples chiffrés pour fixer le taux d'échange (défaut)\n\n");
        sb.append("**Flip-flops Bear en Bull** (Bear < ").append(TrendMixKpi.FLIPFLOP_DAYS).append(" j encadré par deux Bull)\n\n| début | fin | durée |\n|---|---|---|\n");
        dk.bearInBull().forEach(ff -> sb.append("| ").append(date(ds, ff.start())).append(" | ").append(date(ds, ff.end())).append(" | ").append(ff.days()).append(" j |\n"));
        sb.append("\n**Flip-flops Bull en Bear**\n\n| début | fin | durée |\n|---|---|---|\n");
        dk.bullInBear().forEach(ff -> sb.append("| ").append(date(ds, ff.start())).append(" | ").append(date(ds, ff.end())).append(" | ").append(ff.days()).append(" j |\n"));
        sb.append("\n**Fronts majeurs** (retard de la bascule dans le bon sens)\n\n| front | pivot | clôture | ATH | bascule | retard | part de la jambe passée | |\n|---|---|---|---|---|---|---|---|\n");
        for (Front fr : dk.fronts()) {
            sb.append("| ").append(fr.high() ? "sommet" : "creux").append(" | ").append(date(ds, fr.pivot())).append(" | ")
                    .append(f(ds.close(fr.pivot()), 0)).append(" | ").append(fr.ath() ? "ATH" : "").append(" | ").append(date(ds, fr.switchDay()))
                    .append(" | ").append(fr.lag()).append(" j | ").append(f(fr.share() * 100, 0)).append(" % | ")
                    .append(fr.missed() ? "manqué" : "").append(" |\n");
        }
        sb.append("\n## 7. Contrôles\n\n- Mix Java = pine : `TrendMixCalculatorTest` (suite existante).\n");
        sb.append("- Graine Monte-Carlo ").append(SEED).append(", ").append(MONTE_CARLO).append(" tirages : rapport reproductible.\n");
        sb.append("- Définitions retenues par le harnais (non précisées par l'étude, à valider) : K10 = plus grande baisse du gain total depuis son plus haut, en % du budget DCA fixe ; K11 rachat = vente suivie d'au moins un achat avant la vente suivante ; K9 = pic de (quantité × clôture) en % du budget fixe ; K20 « à temps » = ± 25 % de la jambe voisine d'un pivot swing.\n");
        return sb.toString();
    }

    /** Perso Generic fixe sur la fenêtre du rejeu comparatif au pine (début 2024-06-10) : à confronter au pine sous TradingView. */
    private static String genericReplayLine(RainbowAtrDataset ds) {
        LocalDate start = LocalDate.parse(REPLAY_START);
        int si = 0;
        while (si < ds.size() && date(ds, si).isBefore(start)) {
            si++;
        }
        if (si >= ds.size()) {
            return "Perso Generic sur la fenêtre du rejeu : fenêtre hors données.";
        }
        List<DayRow> r = RainbowAtrReplay.run(ds, new RainbowAtrParamSet[]{RainbowAtrPresets.generic()},
                RainbowSetSelector.fixed(ds.size(), 0), si, ds.size() - 1);
        DayRow l = r.getLast();
        return String.format(Locale.ROOT, "Perso Generic fixe depuis %s (fenêtre de `RainbowAtrReplayMain`, à comparer au pine) : investi %.2f, réalisé %.2f, potentiel %.2f, total %.2f, DCA fixe %.2f.",
                REPLAY_START, l.invested(), l.realizedGain(), l.potentialGain(), l.totalGain(), l.fixedGain());
    }

    private static String rowLine(Row r) {
        Kpis k = r.k();
        StringBuilder sb = new StringBuilder("| ").append(r.label()).append(" | ").append(k.switches()).append(" | ").append(f(k.nervosity(), 2))
                .append(" | ").append(f(k.medianSegment(), 0)).append(" | ").append(f(k.bearInBullDays(), 0)).append(" | ")
                .append(f(k.bullInBearWeightedDays(), 0)).append(" | ").append(f(k.topShareWeighted() * 100, 1)).append(" | ")
                .append(f(k.bottomShare() * 100, 1)).append(" | ").append(f(r.stability() * 100, 0)).append(" % | ")
                .append(f(k.counterTrendWeighted(), 3)).append(" | ");
        if (r.rb() == null) {
            sb.append("n/a | n/a | n/a | n/a |\n");
        } else {
            sb.append(f(r.rb().excessPct(), 1)).append(" | ").append(f(r.rb().peakPct(), 0)).append(" | ")
                    .append(f(r.rb().drawdownPct(), 1)).append(" | ").append(r.rb().sells()).append("/").append(r.rb().rebuys()).append(" |\n");
        }
        return sb.toString();
    }

    private static String baseLine(String name, Rainbow r) {
        return String.format(Locale.ROOT, "| %s | %.0f | %.0f | %.1f | %.0f | %.0f | %.1f | %d / %d |%n", name, r.totalGain(), r.scaledGain(), r.excessPct(),
                r.deployedPct(), r.peakPct(), r.drawdownPct(), r.sells(), r.rebuys());
    }

    private static String ablLine(String name, Kpis k) {
        return "| " + name + " | " + k.switches() + " | " + f(k.nervosity(), 2) + " | " + f(k.medianSegment(), 0) + " | "
                + f(k.bearInBullDays(), 0) + " | " + f(k.bullInBearWeightedDays(), 0) + " | " + f(k.topShareWeighted() * 100, 1) + " | "
                + f(k.bottomShare() * 100, 1) + " | " + f(k.accord(), 2) + " | " + f(k.counterTrendWeighted(), 3) + " |\n";
    }

    // ------------------------------------------------------------------ CSV

    private static String kpiCsv(List<Candidate> cands, List<Row> rows) {
        StringBuilder sb = new StringBuilder("candidat,short,medium,long,enter,exit,confirm,sma,atr,k,switches,swing_pivots,k1,k2_median_segment,"
                + "k3_n,k3_days,k4_n,k4_days,k5a_lag_ath,k5a_share_ath,k5a_lag_other,k5a_share_other,k5a_share_weighted,k5b_lag,k5b_share,"
                + "k6_stability,k7_accord,k7b_bull_in_down,k7b_bear_in_up,k7b_weighted,k20_sma_switches,k20_mistimed,"
                + "k8_excess_pct,k8_total_gain,k9_peak_pct,k10_drawdown_pct,k11_sells,k11_rebuys,deployed_pct\n");
        for (int i = 0; i < rows.size(); i++) {
            Params p = cands.get(i).params();
            Row r = rows.get(i);
            Kpis k = r.k();
            sb.append(r.label()).append(',').append(p.shortWindow()).append(',').append(p.mediumWindow()).append(',').append(p.longWindow())
                    .append(',').append(p.enter()).append(',').append(p.exit()).append(',').append(p.confirm()).append(',').append(p.smaPeriod())
                    .append(',').append(p.atrPeriod()).append(',').append(p.atrMultiplier());
            double[] v = {k.switches(), k.swingPivots(), k.nervosity(), k.medianSegment(), k.bearInBull().size(), k.bearInBullDays(),
                    k.bullInBear().size(), k.bullInBearDays(), k.topLag(true), k.topShare(true), k.topLag(false), k.topShare(false),
                    k.topShareWeighted(), k.bottomLag(), k.bottomShare(), r.stability(), k.accord(), k.bullInDownDays(), k.bearInUpDays(),
                    k.counterTrendWeighted(), k.smaSwitches(), k.smaMistimed(),
                    r.rb() == null ? Double.NaN : r.rb().excessPct(), r.rb() == null ? Double.NaN : r.rb().totalGain(),
                    r.rb() == null ? Double.NaN : r.rb().peakPct(), r.rb() == null ? Double.NaN : r.rb().drawdownPct(),
                    r.rb() == null ? Double.NaN : r.rb().sells(), r.rb() == null ? Double.NaN : r.rb().rebuys(),
                    r.rb() == null ? Double.NaN : r.rb().deployedPct()};
            for (double d : v) {
                sb.append(',').append(Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.6g", d));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String regimesCsv(RainbowAtrDataset ds, double[] close, TrendMixCalculator.Result r, List<int[]> swing,
                                     List<int[]> major, int from) {
        String[] sw = new String[close.length];
        String[] mj = new String[close.length];
        swing.forEach(p -> sw[p[0]] = p[1] == ZigZag.HIGH ? "H" : "L");
        major.forEach(p -> mj[p[0]] = p[1] == ZigZag.HIGH ? "H" : "L");
        StringBuilder sb = new StringBuilder("date,close,mix,regression,sma,source,swing_pivot,major_pivot\n");
        for (int i = 0; i < close.length; i++) {
            if (r.regime()[i] == null) {
                continue;
            }
            sb.append(date(ds, i)).append(',').append(close[i]).append(',').append(r.regime()[i]).append(',').append(r.regression()[i])
                    .append(',').append(r.sma()[i]).append(',').append(r.source()[i]).append(',').append(sw[i] == null ? "" : sw[i])
                    .append(',').append(mj[i] == null ? "" : mj[i]).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ utilitaires

    private static LocalDate date(RainbowAtrDataset ds, int i) {
        return Instant.ofEpochMilli(ds.time(i)).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static String f(double v, int digits) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static double std(double[] v) {
        double[] x = Arrays.stream(v).filter(d -> !Double.isNaN(d)).toArray();
        if (x.length == 0) {
            return Double.NaN;
        }
        double m = Arrays.stream(x).average().orElse(0);
        return Math.sqrt(Arrays.stream(x).map(d -> (d - m) * (d - m)).sum() / x.length);
    }

    private static double avg(List<Row> rows, ToDoubleFunction<Row> f) {
        return rows.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    /** Percentile (rang le plus proche) d'un tableau trié. */
    private static double pct(double[] sorted, double p) {
        double[] x = Arrays.stream(sorted).filter(d -> !Double.isNaN(d)).toArray();
        return x.length == 0 ? Double.NaN : x[Math.min(x.length - 1, (int) Math.floor(p * x.length))];
    }
}
