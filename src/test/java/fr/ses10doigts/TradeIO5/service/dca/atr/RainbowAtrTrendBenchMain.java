package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

/**
 * Bench de variantes de Trend (moins sensible) → jeu Bull/Bear du Rainbow ATR, sur la plage du rejeu.
 * Usage : {@code RainbowAtrTrendBenchMain <dir> [début=2024-06-10] [fin]} (CSV d1_<ACTIF>.csv de RainbowAtrReplayMain).
 * Trend de bench locale (score tanh(pente)×r2 multi-fenêtres + hystérésis + confirmation), RANGE = garder le jeu.
 */
public final class RainbowAtrTrendBenchMain {

    private static final String[] ASSETS = {"BTC", "ETH", "PAXG"};
    private static final double ZIGZAG = 0.20;

    /** Régimes par bougie (null = indéfini). */
    interface Trend extends Function<double[], TrendRegime[]> { }

    /** Score multi-fenêtres + hystérésis(enter, exit) + confirmation de {@code confirm} jours. */
    static Trend regression(int[] windows, double scaleMid, double enter, double exit, int confirm) {
        return c -> {
            int n = c.length, maxW = Arrays.stream(windows).max().getAsInt();
            TrendRegime[] out = new TrendRegime[n];
            TrendRegime raw = TrendRegime.RANGE, applied = TrendRegime.RANGE;
            int diff = 0;
            for (int i = maxW - 1; i < n; i++) {
                double s = 0;
                for (int w : windows) {
                    s += Math.tanh(ols(c, i, w, true) * scaleMid) * ols(c, i, w, false);
                }
                s /= windows.length;
                if (raw == TrendRegime.UP && s < exit) raw = TrendRegime.RANGE;
                else if (raw == TrendRegime.DOWN && s > -exit) raw = TrendRegime.RANGE;
                else if (raw == TrendRegime.RANGE) {
                    if (s > enter) raw = TrendRegime.UP; else if (s < -enter) raw = TrendRegime.DOWN;
                }
                if (raw == applied) diff = 0;
                else if (++diff >= confirm) { applied = raw; diff = 0; }
                out[i] = applied;
            }
            return out;
        };
    }

    /** Prix vs SMA(n) avec bande ±band (hystérésis) : UP si close > sma×(1+band), DOWN si < sma×(1-band). */
    static Trend priceMa(int n0, double band) {
        return c -> {
            int n = c.length;
            TrendRegime[] out = new TrendRegime[n];
            TrendRegime cur = TrendRegime.RANGE;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += c[i];
                if (i >= n0) sum -= c[i - n0];
                if (i < n0 - 1) continue;
                double sma = sum / n0;
                if (c[i] > sma * (1 + band)) cur = TrendRegime.UP;
                else if (c[i] < sma * (1 - band)) cur = TrendRegime.DOWN;
                out[i] = cur;
            }
            return out;
        };
    }

    /** Double Trend : le lent tranche s'il est UP/DOWN, sinon on suit le rapide. */
    static Trend dual(Trend slow, Trend fast) {
        return c -> {
            TrendRegime[] s = slow.apply(c), f = fast.apply(c);
            TrendRegime[] out = new TrendRegime[c.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = s[i] == TrendRegime.UP || s[i] == TrendRegime.DOWN ? s[i] : f[i];
            }
            return out;
        };
    }

    /** pente normalisée (slope=true) ou r2 de la régression des clôtures [i-w+1..i]. */
    static double ols(double[] c, int i, int w, boolean slope) {
        double sx = 0, sy = 0, sxy = 0, sx2 = 0;
        for (int k = 0; k < w; k++) { double y = c[i - w + 1 + k]; sx += k; sy += y; sxy += k * y; sx2 += (double) k * k; }
        double mx = sx / w, my = sy / w, den = sx2 - w * mx * mx;
        double sl = den == 0 ? 0 : (sxy - w * mx * my) / den, ic = my - sl * mx;
        if (slope) return my == 0 ? 0 : sl / my;
        double res = 0, tot = 0;
        for (int k = 0; k < w; k++) { double y = c[i - w + 1 + k]; double p = sl * k + ic; res += (y - p) * (y - p); tot += (y - my) * (y - my); }
        return tot == 0 ? 0 : Math.max(0, 1 - res / tot);
    }

    record Variant(String name, Trend trend) { }

    static List<Variant> variants() {
        int[] base = {7, 14, 30};
        List<Variant> v = new ArrayList<>();
        v.add(new Variant("BASE 7/14/30 e1/6 x0", regression(base, 400, 1.0 / 6, 0, 1)));
        v.add(new Variant("hyst e0.30 x0", regression(base, 400, 0.30, 0, 1)));
        v.add(new Variant("hyst e0.45 x0", regression(base, 400, 0.45, 0, 1)));
        v.add(new Variant("hyst e0.30 x-0.15", regression(base, 400, 0.30, -0.15, 1)));
        v.add(new Variant("confirm 7j", regression(base, 400, 1.0 / 6, 0, 7)));
        v.add(new Variant("confirm 15j", regression(base, 400, 1.0 / 6, 0, 15)));
        v.add(new Variant("win 14/30/60", regression(new int[]{14, 30, 60}, 400, 1.0 / 6, 0, 1)));
        v.add(new Variant("win 30/60/120", regression(new int[]{30, 60, 120}, 400, 1.0 / 6, 0, 1)));
        v.add(new Variant("win 30/90/180", regression(new int[]{30, 90, 180}, 400, 1.0 / 6, 0, 1)));
        v.add(new Variant("win 14/30/60 e0.30", regression(new int[]{14, 30, 60}, 400, 0.30, 0, 1)));
        v.add(new Variant("win 30/60/120 e0.30", regression(new int[]{30, 60, 120}, 400, 0.30, 0, 1)));
        v.add(new Variant("win 14/30/60 e0.30 c7", regression(new int[]{14, 30, 60}, 400, 0.30, 0, 7)));
        int[] sl = {30, 90, 180};
        v.add(new Variant("DUAL lent30/90/180 e0.45 + rapide BASE", dual(regression(sl, 400, 0.45, 0, 1), regression(base, 400, 1.0 / 6, 0, 1))));
        v.add(new Variant("DUAL lent30/90/180 e0.30 + rapide BASE", dual(regression(sl, 400, 0.30, 0, 1), regression(base, 400, 1.0 / 6, 0, 1))));
        v.add(new Variant("DUAL lent30/60/120 e0.45 + rapide 14/30/60", dual(regression(new int[]{30, 60, 120}, 400, 0.45, 0, 1), regression(new int[]{14, 30, 60}, 400, 0.30, 0, 1))));
        v.add(new Variant("prix/SMA100 ±3%", priceMa(100, 0.03)));
        v.add(new Variant("prix/SMA100 ±6%", priceMa(100, 0.06)));
        v.add(new Variant("prix/SMA50 ±5%", priceMa(50, 0.05)));
        v.add(new Variant("DUAL prix/SMA100 ±6% + rapide BASE", dual(priceMa(100, 0.06), regression(base, 400, 1.0 / 6, 0, 1))));
        return v;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/rainbow-replay");
        LocalDate start = LocalDate.parse(args.length > 1 ? args[1] : "2024-06-10");
        LocalDate end = args.length > 2 ? LocalDate.parse(args[2]) : null;

        List<Variant> vs = variants();
        Map<String, double[]> agg = new LinkedHashMap<>(); // somme: switches, medSeg, bull%, total, lag, loss, missed
        for (Variant v : vs) agg.put(v.name(), new double[8]);

        for (String asset : ASSETS) {
            List<MarketData> candles = load(dir.resolve("d1_" + asset + ".csv"), asset);
            int n = candles.size();
            double[] close = candles.stream().mapToDouble(m -> m.getClose().doubleValue()).toArray();
            RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
            int si = first(candles, start), ei = end == null ? n - 1 : last(candles, end);
            RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]);
            List<DayRow> bull = RainbowAtrReplay.run(ds, sets, RainbowSetSelector.fixed(n, RainbowAtrPresets.BULL), si, ei);
            List<DayRow> bear = RainbowAtrReplay.run(ds, sets, RainbowSetSelector.fixed(n, RainbowAtrPresets.BEAR), si, ei);
            List<int[]> pivots = zigzag(close, si, ei);
            System.out.printf(Locale.ROOT, "%n== %s  jours=%d  FIXED_BEAR total=%.0f  FIXED_BULL total=%.0f  DCAfixe=%.0f  pivots(zigzag %.0f%%)=%d%n",
                    asset, ei - si + 1, bear.getLast().totalGain(), bull.getLast().totalGain(), bull.getLast().fixedGain(),
                    ZIGZAG * 100, pivots.size());
            System.out.printf("%-44s %4s %6s %5s %8s %8s %6s %6s %6s%n", "variante", "sw", "medSeg", "%bull", "total", "invest", "lag(j)", "perte%", "dipsMq");
            for (Variant v : vs) {
                int[] set = RainbowSetSelector.select(v.trend().apply(close), RangeMapping.KEEP_PREVIOUS);
                List<DayRow> rows = RainbowAtrReplay.run(ds, sets, set, si, ei);
                // segments
                List<Integer> segs = new ArrayList<>();
                int sw = 0, run = 1, bulls = 0;
                for (int i = si; i <= ei; i++) {
                    if (set[i] == RainbowAtrPresets.BULL) bulls++;
                    if (i > si) { if (set[i] != set[i - 1]) { sw++; segs.add(run); run = 1; } else run++; }
                }
                segs.add(run);
                Collections.sort(segs);
                double med = segs.get(segs.size() / 2);
                // lag / perte aux pivots
                double lagSum = 0, lossSum = 0; int legs = 0;
                for (int p = 0; p + 1 < pivots.size(); p++) {
                    int a = pivots.get(p)[0], b = pivots.get(p + 1)[0];
                    boolean up = pivots.get(p)[1] == -1; // pivot bas -> jambe haussière
                    int want = up ? RainbowAtrPresets.BULL : RainbowAtrPresets.BEAR;
                    int d = a; while (d < b && set[d] != want) d++;
                    double move = Math.abs(close[b] - close[a]);
                    double lost = Math.abs(close[Math.min(d, b)] - close[a]);
                    lagSum += d - a; lossSum += move == 0 ? 0 : lost / move; legs++;
                }
                // dips manqués : jours où FIXED_BULL achète (ZONE/TRIGGERED), variante en Bear sans achat
                int missed = 0;
                for (int k = 0; k < rows.size(); k++) {
                    if (bull.get(k).buyKind() != RainbowSignal.BuyKind.NONE && set[si + k] == RainbowAtrPresets.BEAR
                            && rows.get(k).buyKind() == RainbowSignal.BuyKind.NONE) missed++;
                }
                DayRow last = rows.getLast();
                System.out.printf(Locale.ROOT, "%-44s %4d %6.0f %5.0f %8.0f %8.0f %6.1f %6.0f %6d%n", v.name(), sw, med,
                        100.0 * bulls / (ei - si + 1), last.totalGain(), last.invested(), legs == 0 ? 0 : lagSum / legs,
                        legs == 0 ? 0 : 100 * lossSum / legs, missed);
                double[] a = agg.get(v.name());
                a[0] += sw; a[1] += med; a[2] += 100.0 * bulls / (ei - si + 1); a[3] += last.totalGain(); a[4] += last.invested();
                a[5] += legs == 0 ? 0 : lagSum / legs; a[6] += legs == 0 ? 0 : 100 * lossSum / legs; a[7] += missed;
            }
        }
        System.out.printf(Locale.ROOT, "%n== MOYENNE/SOMME 3 actifs (sw,medSeg,%%bull,lag,perte = moyennes ; total,invest,dips = sommes)%n");
        System.out.printf("%-44s %4s %6s %5s %8s %8s %6s %6s %6s%n", "variante", "sw", "medSeg", "%bull", "total", "invest", "lag(j)", "perte%", "dipsMq");
        for (Variant v : vs) {
            double[] a = agg.get(v.name());
            System.out.printf(Locale.ROOT, "%-44s %4.0f %6.0f %5.0f %8.0f %8.0f %6.1f %6.0f %6.0f%n", v.name(), a[0] / 3, a[1] / 3,
                    a[2] / 3, a[3], a[4], a[5] / 3, a[6] / 3, a[7]);
        }
    }

    /** Pivots zigzag (retournement ≥ ZIGZAG) sur [si, ei] : {index, +1 haut / -1 bas}. Causalité non requise (mesure a posteriori). */
    static List<int[]> zigzag(double[] c, int si, int ei) {
        List<int[]> p = new ArrayList<>();
        int ext = si; boolean up = true; boolean decided = false;
        int hi = si, lo = si;
        for (int i = si; i <= ei; i++) {
            if (!decided) {
                if (c[i] > c[hi]) hi = i;
                if (c[i] < c[lo]) lo = i;
                if (c[i] >= c[lo] * (1 + ZIGZAG) && lo < i) { p.add(new int[]{lo, -1}); up = true; ext = i; decided = true; }
                else if (c[i] <= c[hi] * (1 - ZIGZAG) && hi < i) { p.add(new int[]{hi, 1}); up = false; ext = i; decided = true; }
                continue;
            }
            if (up) {
                if (c[i] > c[ext]) ext = i;
                else if (c[i] <= c[ext] * (1 - ZIGZAG)) { p.add(new int[]{ext, 1}); up = false; ext = i; }
            } else {
                if (c[i] < c[ext]) ext = i;
                else if (c[i] >= c[ext] * (1 + ZIGZAG)) { p.add(new int[]{ext, -1}); up = true; ext = i; }
            }
        }
        return p;
    }

    private static List<MarketData> load(Path csv, String asset) throws IOException {
        List<MarketData> out = new ArrayList<>();
        for (String line : Files.readAllLines(csv)) {
            String[] p = line.split(",");
            out.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochMilli(Long.parseLong(p[0])))
                    .pair(asset + "USDT").open(new BigDecimal(p[1])).high(new BigDecimal(p[2])).low(new BigDecimal(p[3]))
                    .close(new BigDecimal(p[4])).volume(BigDecimal.ZERO).build());
        }
        return out;
    }

    private static LocalDate day(MarketData m) { return m.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate(); }

    private static int first(List<MarketData> c, LocalDate d) {
        for (int i = 0; i < c.size(); i++) if (!day(c.get(i)).isBefore(d)) return i;
        throw new IllegalArgumentException();
    }

    private static int last(List<MarketData> c, LocalDate d) {
        for (int i = c.size() - 1; i >= 0; i--) if (!day(c.get(i)).isAfter(d)) return i;
        throw new IllegalArgumentException();
    }
}
