package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendAnalyzer;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendState;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.ToDoubleFunction;

/**
 * Bench Rainbow DCA ATR v3 (moteur {@link RainbowAtrEngine}, fidèle au pine) sur BTC D1 2017-2026
 * ({@code tools/calibration/btcusdt_klines_d1_full.json} converti en {@code target/rainbow-atr/d1.csv}).
 * <p>
 * Question posée : vaut-il le coup d'avoir un paramétrage différent par régime de Trend
 * ({@link TrendAnalyzer}, UP/DOWN/RANGE, causal) plutôt qu'un seul paramétrage global ?
 * <p>
 * Méthode : fenêtres à démarrage à froid de {@value #WINDOW} bougies (pas {@value #STRIDE}) ; objectif
 * = score de Clem (moyenne harmonique realized/potential, pénalité si realized &lt; 50 % du gain) calculé
 * en % du budget du DCA fixe (comparable entre fenêtres) et moyenné sur les fenêtres ; recherche = coordinate
 * ascent multi-départs. Validation = walk-forward annuel : optimisation sur les fenêtres ENTIÈREMENT
 * antérieures à l'année testée, évaluation sur les fenêtres entièrement dans l'année testée. Mode GLOBAL
 * = 1 jeu de paramètres ; mode RÉGIME = 3 jeux (UP/DOWN/RANGE, pilotés bougie par bougie par la Trend,
 * état de la machine conservé lors des changements) ; ATH/moon globaux dans les deux cas.
 * Usage : {@code java ... RainbowAtrRegimeBench <dir>} (dir contient d1.csv ; écrit bench-report.md/csv).
 */
public final class RainbowAtrRegimeBench {

    static final int WINDOW = 180;
    static final int STRIDE = 30;
    static final int FIRST_START = 120;
    static final int UP = 0;
    static final int DOWN = 1;
    static final int RANGE = 2;
    static final String[] REGIME_NAMES = {"UP", "DOWN", "RANGE"};

    record Window(int start, int end) { }

    record Point(RainbowAtrTuning[] tunings, RainbowAtrGlobals g) {
        Point withTuning(int idx, RainbowAtrTuning t) {
            RainbowAtrTuning[] c = tunings.clone();
            c[idx] = t;
            return new Point(c, g);
        }
    }

    record Slot(int tuningIdx, String name, Object[] grid) { }

    // ---- grilles ----
    static final Map<String, Object[]> TUNING_GRID = new LinkedHashMap<>();
    static final Map<String, Object[]> GLOBAL_GRID = new LinkedHashMap<>();

    static {
        TUNING_GRID.put("smaPeriod", new Object[]{10, 14, 20, 26, 36, 50, 75, 100});
        TUNING_GRID.put("atrPeriod", new Object[]{7, 10, 14, 21, 28});
        TUNING_GRID.put("atrMultDown2", new Object[]{1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0});
        TUNING_GRID.put("atrMultDown1", new Object[]{0.5, 1.0, 1.5, 2.0, 3.0});
        TUNING_GRID.put("atrMultUp1", new Object[]{0.0, 0.5, 1.0, 1.5, 2.0});
        TUNING_GRID.put("atrMultUp2", new Object[]{0.0, 0.5, 1.0, 2.0, 3.0, 4.0});
        TUNING_GRID.put("atrMultUp3", new Object[]{1.0, 2.0, 2.5, 3.0, 3.5, 4.0, 5.0, 6.0});
        TUNING_GRID.put("buyReentryMode", ReentryMode.values());
        TUNING_GRID.put("sellReentryMode", ReentryMode.values());
        TUNING_GRID.put("trailingStopBuyPct", new Object[]{2.0, 3.0, 5.0, 7.0, 10.0});
        TUNING_GRID.put("trailingStopSellPct", new Object[]{2.0, 3.0, 5.0, 7.0, 10.0});
        TUNING_GRID.put("cooldownDays", new Object[]{0, 1, 3, 7, 12, 20});
        TUNING_GRID.put("fixedDelayDays", new Object[]{3, 5, 10, 15, 20, 30, 45, 60});
        TUNING_GRID.put("sellFraction", new Object[]{0.05, 0.10, 0.20, 0.25, 0.33, 0.40, 0.50, 1.0});
        TUNING_GRID.put("allowSellDuringCooldown", new Object[]{false, true});
        TUNING_GRID.put("cooldownAfterSellOn", new Object[]{true, false});
        TUNING_GRID.put("blockBuyAfterSellUntilDown2", new Object[]{true, false});

        GLOBAL_GRID.put("athOn", new Object[]{true, false});
        GLOBAL_GRID.put("athRefDdBuyPct", new Object[]{15.0, 20.0, 30.0, 45.0, 60.0, 80.0});
        GLOBAL_GRID.put("athRefDdSellPct", new Object[]{5.0, 10.0, 15.0, 30.0, 45.0, 60.0});
        GLOBAL_GRID.put("athBuyMin", new Object[]{0.0, 0.25, 0.5, 1.0});
        GLOBAL_GRID.put("athBuyMax", new Object[]{1.0, 1.5, 2.0, 3.0, 4.0, 5.0});
        GLOBAL_GRID.put("athSellMax", new Object[]{1.0, 2.0, 3.0});
        GLOBAL_GRID.put("athSellMin", new Object[]{0.25, 0.5, 1.0});
        GLOBAL_GRID.put("moonOn", new Object[]{true, false});
        GLOBAL_GRID.put("moonReservePct", new Object[]{0.0, 25.0, 50.0, 75.0});
        GLOBAL_GRID.put("moonReserveRatchet", new Object[]{false, true});
        GLOBAL_GRID.put("moonTrailingStopPct", new Object[]{8.0, 10.0, 15.0, 20.0, 30.0});
        GLOBAL_GRID.put("moonStopSellPct", new Object[]{50.0, 100.0});
    }

    // ---- données ----
    final RainbowAtrDataset ds;
    final int[] regimeOfBar;
    final int[] yearOfBar;
    final int n;
    final List<Window> allWindows = new ArrayList<>();

    RainbowAtrRegimeBench(Path dir) throws IOException {
        List<String> rows = Files.readAllLines(dir.resolve("d1.csv"));
        n = rows.size();
        List<MarketData> candles = new ArrayList<>(n);
        yearOfBar = new int[n];
        for (int i = 0; i < n; i++) {
            String[] p = rows.get(i).split(",");
            Instant ts = Instant.ofEpochMilli(Long.parseLong(p[0]));
            yearOfBar[i] = ts.atZone(ZoneOffset.UTC).getYear();
            candles.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(ts).pair("BTCUSDT")
                    .open(new BigDecimal(p[3])).high(new BigDecimal(p[1])).low(new BigDecimal(p[2]))
                    .close(new BigDecimal(p[3])).volume(BigDecimal.ZERO).build());
        }
        ds = RainbowAtrDataset.fromMarketData(candles);
        // Trend de production (régression multi-fenêtres + hystérésis), causale, réglages par défaut.
        List<TrendState> states = new TrendAnalyzer().analyzeTimeline(candles, 400.0, 1.0 / 3, 1.0 / 3, 1.0 / 3, false);
        regimeOfBar = new int[n];
        int offset = n - states.size();
        for (int i = 0; i < n; i++) {
            if (i < offset) { regimeOfBar[i] = RANGE; continue; }
            regimeOfBar[i] = switch (states.get(i - offset).regime()) { case UP -> UP; case DOWN -> DOWN; case RANGE -> RANGE; };
        }
        for (int s = FIRST_START; s + WINDOW - 1 < n; s += STRIDE) {
            allWindows.add(new Window(s, s + WINDOW - 1));
        }
    }

    // ---- objectifs ----
    /**
     * Score de Clem (harmonique realized/potential, pénalité proportionnelle si realized &lt; 50 % du gain),
     * en % du budget du DCA fixe de la fenêtre (fixedInvested), à capital déployé plafonné à ce budget ({@link #budgetScale}). Toutes les fenêtres ayant la même longueur et
     * la même base, c'est exactement le score € du bench historique à une constante près, mais comparable
     * si la longueur change.
     */
    static double clemScore(RainbowAtrResult r) {
        double budget = r.fixedInvested();
        if (budget <= 0) { return -1000; }
        double scale = budgetScale(r);
        double realized = r.realizedGain() * scale / budget * 100;
        double potential = r.potentialGain() * scale / budget * 100;
        if (realized <= 0 || potential <= 0) { return Math.min(realized, potential); }
        double h = 2 * realized * potential / (realized + potential);
        double ratio = realized / (realized + potential);
        return ratio < 0.5 ? h * ratio / 0.5 : h;
    }

    /**
     * Contrainte de capital : si la stratégie déploie plus que le budget du DCA fixe (facteurs ATH et
     * multiplicateurs de zone peuvent dépasser 1), ses gains sont ramenés à budget égal (équivalent à
     * réduire le montant de base). Le sous-déploiement n'est pas récompensé (cash oisif = 0).
     */
    static double budgetScale(RainbowAtrResult r) {
        return r.invested() > r.fixedInvested() ? r.fixedInvested() / r.invested() : 1.0;
    }

    /** Excès de gain total (vendu + restant - investi) vs DCA fixe, en % du budget du DCA fixe. */
    static double excessVsFixed(RainbowAtrResult r) {
        double budget = r.fixedInvested();
        return budget <= 0 ? -1000 : (r.totalGain() * budgetScale(r) - r.fixedGain()) / budget * 100;
    }

    RainbowAtrResult run(Point p, Window w, boolean trace) {
        int[] regimes = p.tunings().length == 1 ? null : regimeOfBar;
        return RainbowAtrEngine.simulate(ds, p.g(), p.tunings(), regimes, w.start(), w.end(), trace);
    }

    double evalMean(Point p, List<Window> ws, ToDoubleFunction<RainbowAtrResult> obj) {
        for (RainbowAtrTuning t : p.tunings()) {
            if (!t.isValid()) { return -1e9; }
        }
        if (ws.isEmpty()) { return 0; }
        double sum = 0;
        for (Window w : ws) { sum += obj.applyAsDouble(run(p, w, false)); }
        return sum / ws.size();
    }

    // ---- recherche ----
    static RainbowAtrTuning setParam(RainbowAtrTuning t, String name, Object v) {
        RainbowAtrTuning x = t.toBuilder().set(name, v).build();
        double d2 = x.atrMultDown2(), d1 = x.atrMultDown1(), u1 = x.atrMultUp1(), u2 = x.atrMultUp2(), u3 = x.atrMultUp3();
        switch (name) {
            case "atrMultDown2" -> d1 = Math.min(d1, d2);
            case "atrMultDown1" -> d2 = Math.max(d2, d1);
            case "atrMultUp1" -> { u2 = Math.max(u2, u1); u3 = Math.max(u3, u2); }
            case "atrMultUp2" -> { u1 = Math.min(u1, u2); u3 = Math.max(u3, u2); }
            case "atrMultUp3" -> { u2 = Math.min(u2, u3); u1 = Math.min(u1, u2); }
            default -> { return x; }
        }
        return x.toBuilder().set("atrMultDown2", d2).set("atrMultDown1", d1).set("atrMultUp1", u1)
                .set("atrMultUp2", u2).set("atrMultUp3", u3).build();
    }

    /** Paramètres autorisés à varier PAR régime dans le mode RÉGIME_LITE (les autres sont partagés par les 3 régimes). */
    static final java.util.Set<String> LITE_NAMES = java.util.Set.of("atrMultDown2", "atrMultUp3", "sellFraction", "smaPeriod");

    static Point withSlot(Point p, Slot s, Object v) {
        if (s.tuningIdx() == -1) {
            return new Point(p.tunings(), p.g().toBuilder().set(s.name(), v).build());
        }
        if (s.tuningIdx() == -2) { // slot partagé : même valeur dans tous les régimes
            RainbowAtrTuning[] c = p.tunings().clone();
            for (int k = 0; k < c.length; k++) { c[k] = setParam(c[k], s.name(), v); }
            return new Point(c, p.g());
        }
        return p.withTuning(s.tuningIdx(), setParam(p.tunings()[s.tuningIdx()], s.name(), v));
    }

    /** @param perRegimeNames null = tous les paramètres de tuning par régime ; sinon seuls ceux-là, les autres partagés. */
    static List<Slot> slots(int nTunings, java.util.Set<String> perRegimeNames) {
        List<Slot> out = new ArrayList<>();
        for (var e : TUNING_GRID.entrySet()) {
            if (nTunings == 1) {
                out.add(new Slot(0, e.getKey(), e.getValue()));
            } else if (perRegimeNames == null || perRegimeNames.contains(e.getKey())) {
                for (int k = 0; k < nTunings; k++) { out.add(new Slot(k, e.getKey(), e.getValue())); }
            } else {
                out.add(new Slot(-2, e.getKey(), e.getValue()));
            }
        }
        for (var e : GLOBAL_GRID.entrySet()) { out.add(new Slot(-1, e.getKey(), e.getValue())); }
        return out;
    }

    record Scored(Point point, double score) { }

    Scored ascend(Point start, List<Window> ws, ToDoubleFunction<RainbowAtrResult> obj, java.util.Set<String> perRegimeNames) {
        Point best = start;
        double bestScore = evalMean(best, ws, obj);
        List<Slot> slots = slots(start.tunings().length, perRegimeNames);
        for (int pass = 0; pass < 12; pass++) {
            boolean improved = false;
            for (Slot s : slots) {
                Point slotBest = best;
                double slotScore = bestScore;
                for (Object v : s.grid()) {
                    Point cand = withSlot(best, s, v);
                    double sc = evalMean(cand, ws, obj);
                    if (sc > slotScore + 1e-9) { slotBest = cand; slotScore = sc; }
                }
                if (slotBest != best) { best = slotBest; bestScore = slotScore; improved = true; }
            }
            if (!improved) { break; }
        }
        return new Scored(best, bestScore);
    }

    static RainbowAtrTuning randomTuning(Random rng) {
        RainbowAtrTuning t = RainbowAtrTuning.pineFinalCandidate();
        for (var e : TUNING_GRID.entrySet()) {
            Object[] g = e.getValue();
            t = setParam(t, e.getKey(), g[rng.nextInt(g.length)]);
        }
        return t.isValid() ? t : RainbowAtrTuning.pineFinalCandidate();
    }

    static RainbowAtrGlobals randomGlobals(Random rng) {
        RainbowAtrGlobals.Builder b = RainbowAtrGlobals.pineDefault().toBuilder();
        for (var e : GLOBAL_GRID.entrySet()) {
            Object[] g = e.getValue();
            b.set(e.getKey(), g[rng.nextInt(g.length)]);
        }
        return b.build();
    }

    static Point replicate(RainbowAtrTuning t, RainbowAtrGlobals g, int k) {
        RainbowAtrTuning[] a = new RainbowAtrTuning[k];
        java.util.Arrays.fill(a, t);
        return new Point(a, g);
    }

    Scored searchGlobal(List<Window> ws, ToDoubleFunction<RainbowAtrResult> obj, long seed, int randomStarts) {
        List<Point> starts = new ArrayList<>();
        starts.add(replicate(RainbowAtrTuning.pineFinalCandidate(), RainbowAtrGlobals.pineDefault(), 1));
        starts.add(replicate(RainbowAtrTuning.pineCustomDefault(), RainbowAtrGlobals.pineDefault(), 1));
        Random rng = new Random(seed);
        for (int i = 0; i < randomStarts; i++) { starts.add(replicate(randomTuning(rng), randomGlobals(rng), 1)); }
        Scored best = null;
        for (Point s : starts) {
            Scored r = ascend(s, ws, obj, null);
            if (best == null || r.score() > best.score()) { best = r; }
        }
        return best;
    }

    Scored searchRegime(Scored global, List<Window> ws, ToDoubleFunction<RainbowAtrResult> obj, long seed, int randomStarts, boolean lite) {
        List<Point> starts = new ArrayList<>();
        starts.add(replicate(global.point().tunings()[0], global.point().g(), 3));
        Random rng = new Random(seed);
        for (int i = 0; i < randomStarts; i++) {
            if (lite) {
                starts.add(replicate(randomTuning(rng), randomGlobals(rng), 3));
            } else {
                starts.add(new Point(new RainbowAtrTuning[]{randomTuning(rng), randomTuning(rng), randomTuning(rng)}, randomGlobals(rng)));
            }
        }
        Scored best = null;
        for (Point s : starts) {
            Scored r = ascend(s, ws, obj, lite ? LITE_NAMES : null);
            if (best == null || r.score() > best.score()) { best = r; }
        }
        return best;
    }

    // ---- walk-forward ----
    record FoldResult(int year, int trainWindows, int testWindows,
                      double trainGlobal, double trainRegime, double trainLite, Point lite,
                      double[] oos /* [mode][metric] aplati : voir MODES/METRICS */, Point global, Point regime) { }

    static final String[] MODES = {"GLOBAL", "REGIME", "REGIME_LITE", "PINE_FINAL", "PINE_CUSTOM"};
    static final String[] METRICS = {"score", "excessVsFixed", "deployed%", "realized%", "potential%", "realizedRatio"};

    List<Window> trainWindows(int year) {
        int firstBar = firstBarOfYear(year);
        List<Window> out = new ArrayList<>();
        for (Window w : allWindows) { if (w.end() < firstBar) { out.add(w); } }
        return out;
    }

    List<Window> testWindows(int year) {
        int firstBar = firstBarOfYear(year);
        int lastBar = lastBarOfYear(year);
        List<Window> out = new ArrayList<>();
        for (Window w : allWindows) { if (w.start() >= firstBar && w.end() <= lastBar) { out.add(w); } }
        return out;
    }

    int firstBarOfYear(int y) { for (int i = 0; i < n; i++) { if (yearOfBar[i] == y) { return i; } } return n; }
    int lastBarOfYear(int y) { for (int i = n - 1; i >= 0; i--) { if (yearOfBar[i] == y) { return i; } } return -1; }

    double[] oosMetrics(Point p, List<Window> ws) {
        double[] m = new double[METRICS.length];
        for (Window w : ws) {
            RainbowAtrResult r = run(p, w, false);
            m[0] += clemScore(r);
            m[1] += excessVsFixed(r);
            double sc = budgetScale(r);
            m[2] += r.invested() / r.fixedInvested() * 100;
            m[3] += r.realizedGain() * sc / r.fixedInvested() * 100;
            m[4] += r.potentialGain() * sc / r.fixedInvested() * 100;
            double rr = r.realizedRatio();
            m[5] += Double.isNaN(rr) ? 0 : rr;
        }
        for (int i = 0; i < m.length; i++) { m[i] /= Math.max(1, ws.size()); }
        return m;
    }

    FoldResult runFold(int year, ToDoubleFunction<RainbowAtrResult> obj, int randomStarts) {
        List<Window> train = trainWindows(year);
        List<Window> test = testWindows(year);
        Scored g = searchGlobal(train, obj, 1000L + year, randomStarts);
        Scored r = searchRegime(g, train, obj, 2000L + year, randomStarts / 2, false);
        Scored l = searchRegime(g, train, obj, 3000L + year, randomStarts / 2, true);
        Point pineFinal = replicate(RainbowAtrTuning.pineFinalCandidate(), RainbowAtrGlobals.pineDefault(), 1);
        Point pineCustom = replicate(RainbowAtrTuning.pineCustomDefault(), RainbowAtrGlobals.pineDefault(), 1);
        double[] flat = new double[MODES.length * METRICS.length];
        Point[] pts = {g.point(), r.point(), l.point(), pineFinal, pineCustom};
        for (int m = 0; m < MODES.length; m++) {
            double[] mm = oosMetrics(pts[m], test);
            System.arraycopy(mm, 0, flat, m * METRICS.length, METRICS.length);
        }
        return new FoldResult(year, train.size(), test.size(), g.score(), r.score(), l.score(), l.point(), flat, g.point(), r.point());
    }

    // ---- affichage ----
    static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    static String describe(RainbowAtrTuning t) {
        return String.format(Locale.ROOT,
                "sma=%d atr=%d | down2=%.1f down1=%.1f up1=%.1f up2=%.1f up3=%.1f | buy=%s sell=%s trailBuy=%.0f trailSell=%.0f cooldown=%d fixedDelay=%d sellFraction=%.2f allowSellCd=%b cooldownOn=%b blockBuy=%b",
                t.smaPeriod(), t.atrPeriod(), t.atrMultDown2(), t.atrMultDown1(), t.atrMultUp1(), t.atrMultUp2(), t.atrMultUp3(),
                t.buyReentryMode(), t.sellReentryMode(), t.trailingStopBuyPct(), t.trailingStopSellPct(),
                t.cooldownDays(), t.fixedDelayDays(), t.sellFraction(), t.allowSellDuringCooldown(), t.cooldownAfterSellOn(), t.blockBuyAfterSellUntilDown2());
    }

    static String describe(RainbowAtrGlobals g) {
        return String.format(Locale.ROOT,
                "athOn=%b refBuy=%.0f refSell=%.0f buyMin=%.2f buyMax=%.2f sellMax=%.2f sellMin=%.2f | moonOn=%b reserve=%.0f ratchet=%b moonTrail=%.0f moonStop=%.0f",
                g.athOn(), g.athRefDdBuyPct(), g.athRefDdSellPct(), g.athBuyMin(), g.athBuyMax(), g.athSellMax(), g.athSellMin(),
                g.moonOn(), g.moonReservePct(), g.moonReserveRatchet(), g.moonTrailingStopPct(), g.moonStopSellPct());
    }

    String objectiveReport(String title, ToDoubleFunction<RainbowAtrResult> obj, int randomStarts, ExecutorService pool) throws Exception {
        List<Integer> years = new ArrayList<>();
        for (int y = yearOfBar[FIRST_START] + 4; y <= yearOfBar[yearOfBar.length - 1]; y++) { years.add(y); }
        List<Future<FoldResult>> futures = new ArrayList<>();
        for (int y : years) {
            Callable<FoldResult> c = () -> runFold(y, obj, randomStarts);
            futures.add(pool.submit(c));
        }
        List<FoldResult> folds = new ArrayList<>();
        for (Future<FoldResult> f : futures) { folds.add(f.get()); }

        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(title).append("\n\n");
        sb.append("### Walk-forward annuel (moyenne des fenêtres de test de l'année)\n\n");
        sb.append("| Année | fen. train/test | train GLOBAL | train RÉGIME | train LITE |");
        for (String m : MODES) { sb.append(" OOS score ").append(m).append(" |"); }
        for (String m : MODES) { sb.append(" OOS excès/DCA ").append(m).append(" |"); }
        sb.append("\n|---|---|---|---|---|").append("---|".repeat(MODES.length * 2)).append("\n");
        double[][] sumMetric = new double[MODES.length][METRICS.length];
        int[] winsScore = new int[1];
        int[] winsExcess = new int[1];
        List<Double> diffScore = new ArrayList<>();
        List<Double> diffExcess = new ArrayList<>();
        List<Double> diffScoreLite = new ArrayList<>();
        List<Double> diffExcessLite = new ArrayList<>();
        int[] winsLite = new int[2];
        for (FoldResult f : folds) {
            sb.append("| ").append(f.year()).append(" | ").append(f.trainWindows()).append("/").append(f.testWindows())
                    .append(" | ").append(fmt(f.trainGlobal())).append(" | ").append(fmt(f.trainRegime())).append(" | ").append(fmt(f.trainLite())).append(" |");
            for (int m = 0; m < MODES.length; m++) { sb.append(' ').append(fmt(f.oos()[m * METRICS.length])).append(" |"); }
            for (int m = 0; m < MODES.length; m++) { sb.append(' ').append(fmt(f.oos()[m * METRICS.length + 1])).append(" |"); }
            sb.append('\n');
            for (int m = 0; m < MODES.length; m++) {
                for (int k = 0; k < METRICS.length; k++) { sumMetric[m][k] += f.oos()[m * METRICS.length + k]; }
            }
            double dS = f.oos()[METRICS.length] - f.oos()[0];
            double dE = f.oos()[METRICS.length + 1] - f.oos()[1];
            diffScore.add(dS);
            diffExcess.add(dE);
            double dSl = f.oos()[2 * METRICS.length] - f.oos()[0];
            double dEl = f.oos()[2 * METRICS.length + 1] - f.oos()[1];
            diffScoreLite.add(dSl);
            diffExcessLite.add(dEl);
            if (dSl > 0) { winsLite[0]++; }
            if (dEl > 0) { winsLite[1]++; }
            if (dS > 0) { winsScore[0]++; }
            if (dE > 0) { winsExcess[0]++; }
        }
        sb.append("\n### Moyenne OOS sur les ").append(folds.size()).append(" années\n\n| Mode |");
        for (String k : METRICS) { sb.append(' ').append(k).append(" |"); }
        sb.append("\n|---|").append("---|".repeat(METRICS.length)).append("\n");
        for (int m = 0; m < MODES.length; m++) {
            sb.append("| ").append(MODES[m]).append(" |");
            for (int k = 0; k < METRICS.length; k++) { sb.append(' ').append(fmt(sumMetric[m][k] / folds.size())).append(" |"); }
            sb.append('\n');
        }
        sb.append("\n**RÉGIME − GLOBAL (OOS)** : score ").append(stats(diffScore)).append(" ; excès/DCA fixe ").append(stats(diffExcess))
                .append(" ; années gagnées score ").append(winsScore[0]).append("/").append(folds.size())
                .append(", excès ").append(winsExcess[0]).append("/").append(folds.size()).append(".\n\n");
        sb.append("**RÉGIME_LITE − GLOBAL (OOS)** : score ").append(stats(diffScoreLite)).append(" ; excès/DCA fixe ").append(stats(diffExcessLite))
                .append(" ; années gagnées score ").append(winsLite[0]).append("/").append(folds.size())
                .append(", excès ").append(winsLite[1]).append("/").append(folds.size()).append(".\n\n");

        sb.append("### Paramètres retenus par fold (optimisés sur le train)\n\n");
        for (FoldResult f : folds) {
            sb.append("**").append(f.year()).append("** GLOBAL : `").append(describe(f.global().tunings()[0])).append("` ; `").append(describe(f.global().g())).append("`\n\n");
            for (int k = 0; k < 3; k++) {
                sb.append("- RÉGIME ").append(REGIME_NAMES[k]).append(" : `").append(describe(f.regime().tunings()[k])).append("`\n");
            }
            sb.append("- RÉGIME globals : `").append(describe(f.regime().g())).append("`\n");
            for (int k = 0; k < 3; k++) {
                sb.append("- LITE ").append(REGIME_NAMES[k]).append(" : `").append(describe(f.lite().tunings()[k])).append("`\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    static String stats(List<Double> d) {
        double mean = d.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = d.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / Math.max(1, d.size() - 1);
        double se = Math.sqrt(var / d.size());
        return String.format(Locale.ROOT, "moy %+.2f (± %.2f ES)", mean, se);
    }

    String regimeDistribution() {
        int[] c = new int[3];
        for (int i = 30; i < n; i++) { c[regimeOfBar[i]]++; }
        int tot = c[0] + c[1] + c[2];
        return String.format(Locale.ROOT, "Répartition des régimes de Trend (D1, %d bougies) : UP %.1f %%, DOWN %.1f %%, RANGE %.1f %%.",
                tot, 100.0 * c[0] / tot, 100.0 * c[1] / tot, 100.0 * c[2] / tot);
    }

    String finalReport(ToDoubleFunction<RainbowAtrResult> obj, int randomStarts) {
        // Optimum "plein échantillon" (in-sample) : à lire avec les résultats walk-forward, pas seul.
        Scored g = searchGlobal(allWindows, obj, 77L, randomStarts);
        Scored r = searchRegime(g, allWindows, obj, 78L, randomStarts / 2, false);
        Scored l = searchRegime(g, allWindows, obj, 79L, randomStarts / 2, true);
        StringBuilder sb = new StringBuilder();
        sb.append("## Optimum plein échantillon (in-sample, ").append(allWindows.size()).append(" fenêtres)\n\n");
        sb.append("Score train : GLOBAL ").append(fmt(g.score())).append(" ; RÉGIME ").append(fmt(r.score())).append(" ; LITE ").append(fmt(l.score())).append("\n\n");
        sb.append("GLOBAL : `").append(describe(g.point().tunings()[0])).append("`\n\nGlobals : `").append(describe(g.point().g())).append("`\n\n");
        for (int k = 0; k < 3; k++) {
            sb.append("RÉGIME ").append(REGIME_NAMES[k]).append(" : `").append(describe(r.point().tunings()[k])).append("`\n\n");
        }
        sb.append("RÉGIME globals : `").append(describe(r.point().g())).append("`\n\n");
        for (int k = 0; k < 3; k++) {
            sb.append("LITE ").append(REGIME_NAMES[k]).append(" : `").append(describe(l.point().tunings()[k])).append("`\n\n");
        }
        return sb.toString();
    }

    /** Jeu "recommandé" (centre des plateaux de sensibilité + consensus des folds walk-forward). */
    static Point recommended(boolean moonOn, double moonReservePct, double moonTrailPct) {
        RainbowAtrTuning t = new RainbowAtrTuning(50, 21, 5.0, 0.5, 2.0, 3.0, 3.5,
                ReentryMode.TRAILING_STOP, ReentryMode.FIXED_DELAY, 3.0, 5.0, 1, 15, 0.25, false, true, true);
        RainbowAtrGlobals g = new RainbowAtrGlobals(true, 30.0, 15.0, 0.25, 4.0, 1.0, 1.0,
                moonOn, moonReservePct, false, moonTrailPct, 100.0, 2.0, 1.0, 0.5, 3.0, 1.0);
        return replicate(t, g, 1);
    }

    String recommendation() {
        Map<String, Point> cands = new LinkedHashMap<>();
        cands.put("PINE_FINAL", replicate(RainbowAtrTuning.pineFinalCandidate(), RainbowAtrGlobals.pineDefault(), 1));
        cands.put("PINE_CUSTOM", replicate(RainbowAtrTuning.pineCustomDefault(), RainbowAtrGlobals.pineDefault(), 1));
        cands.put("RECO_MOON_OFF", recommended(false, 0, 15));
        cands.put("RECO_MOON_25", recommended(true, 25, 20));
        cands.put("RECO_MOON_50", recommended(true, 50, 15));
        StringBuilder sb = new StringBuilder("## Jeu recommandé vs presets pine — par année (fenêtres de 180 j terminant dans l'année)\n\n");
        sb.append("RECO : `").append(describe(cands.get("RECO_MOON_OFF").tunings()[0])).append("` ; `").append(describe(cands.get("RECO_MOON_OFF").g())).append("`\n\n");
        sb.append("Cellule = excès vs DCA fixe (% budget) / score Clem / capital déployé % — moyenne des fenêtres.\n\n| Année | fen. |");
        for (String c : cands.keySet()) { sb.append(' ').append(c).append(" |"); }
        sb.append("\n|---|---|").append("---|".repeat(cands.size())).append("\n");
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (String c : cands.keySet()) { totals.put(c, new double[4]); }
        int years = 0;
        for (int y = yearOfBar[FIRST_START] + 1; y <= yearOfBar[yearOfBar.length - 1]; y++) {
            List<Window> ws = new ArrayList<>();
            for (Window w : allWindows) { if (yearOfBar[w.end()] == y) { ws.add(w); } }
            if (ws.isEmpty()) { continue; }
            years++;
            sb.append("| ").append(y).append(" | ").append(ws.size()).append(" |");
            for (var e : cands.entrySet()) {
                double[] m = oosMetrics(e.getValue(), ws);
                sb.append(' ').append(fmt(m[1])).append(" / ").append(fmt(m[0])).append(" / ").append(fmt(m[2])).append(" |");
                double[] tt = totals.get(e.getKey());
                tt[0] += m[1]; tt[1] += m[0]; tt[2] += m[2]; tt[3] = Math.min(tt[3], m[1]);
            }
            sb.append('\n');
        }
        sb.append("| **moyenne** | |");
        for (var e : totals.entrySet()) {
            double[] tt = e.getValue();
            sb.append(" **").append(fmt(tt[0] / years)).append(" / ").append(fmt(tt[1] / years)).append(" / ").append(fmt(tt[2] / years)).append("** (pire année excès ").append(fmt(tt[3])).append(") |");
        }
        sb.append("\n\n");
        return sb.toString();
    }

    String sensitivity(ToDoubleFunction<RainbowAtrResult> obj, int randomStarts) {
        Scored g = searchGlobal(allWindows, obj, 77L, randomStarts);
        StringBuilder sb = new StringBuilder("## Sensibilité du GLOBAL plein échantillon (un paramètre varie, les autres fixes)\n\n");
        sb.append("Score de référence : ").append(fmt(g.score())).append("\n\n| Paramètre | valeur → score |\n|---|---|\n");
        for (Slot s : slots(1, null)) {
            sb.append("| ").append(s.name()).append(" |");
            for (Object v : s.grid()) {
                Point c = withSlot(g.point(), s, v);
                sb.append(' ').append(v).append(" → ").append(fmt(evalMean(c, allWindows, obj))).append(';');
            }
            sb.append(" |\n");
        }
        sb.append("\nPresets pine sur les mêmes fenêtres : FINAL ").append(fmt(evalMean(replicate(RainbowAtrTuning.pineFinalCandidate(), RainbowAtrGlobals.pineDefault(), 1), allWindows, obj)))
                .append(" ; CUSTOM ").append(fmt(evalMean(replicate(RainbowAtrTuning.pineCustomDefault(), RainbowAtrGlobals.pineDefault(), 1), allWindows, obj))).append("\n\n");
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int randomStarts = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        RainbowAtrRegimeBench b = new RainbowAtrRegimeBench(dir);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            StringBuilder md = new StringBuilder("# Rainbow DCA ATR v3 — bench global vs paramétrage par régime de Trend\n\n");
            md.append(b.regimeDistribution()).append("\n\n");
            md.append(b.allWindows.size()).append(" fenêtres de ").append(WINDOW).append(" jours (pas ").append(STRIDE).append("), démarrage à froid. Scores/gains en % du budget du DCA fixe (base × jours de la fenêtre).\n\n");
            md.append(b.objectiveReport("Objectif A — score de Clem (harmonique realized/potential, pénalité < 50 % réalisé)", RainbowAtrRegimeBench::clemScore, randomStarts, pool));
            md.append(b.objectiveReport("Objectif B — excès de gain vs DCA fixe, % du budget DCA fixe (contrôle de robustesse)", RainbowAtrRegimeBench::excessVsFixed, randomStarts, pool));
            md.append(b.finalReport(RainbowAtrRegimeBench::clemScore, randomStarts));
            md.append(b.sensitivity(RainbowAtrRegimeBench::clemScore, randomStarts));
            md.append(b.recommendation());
            Files.writeString(dir.resolve("bench-report.md"), md.toString());
            System.out.println("OK " + dir.resolve("bench-report.md"));
        } finally {
            pool.shutdown();
        }
    }
}
