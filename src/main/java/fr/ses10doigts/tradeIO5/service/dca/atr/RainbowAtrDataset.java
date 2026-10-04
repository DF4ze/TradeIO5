package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Série D1 en {@code double[]} + caches des séries dérivées (SMA, ATR, ATH, drapeaux To the moon),
 * partagés entre toutes les simulations d'un bench (calcul unique par période). Thread-safe en lecture.
 * <p>
 * Fidélité pine : SMA = {@code ta.sma} (NaN avant {@code period} bougies) ; ATR = {@code ta.atr}
 * (RMA du True Range, TR[0] = high-low, amorçage = moyenne simple des {@code period} premiers TR, donc
 * valide dès la bougie {@code period-1}) ; ATH = plus haut courant (inclut la bougie) calculé depuis la
 * PREMIÈRE bougie de la série, jamais depuis le début de la fenêtre rejouée ; drapeaux moon calculés
 * eux aussi sur toute la série.
 */
public final class RainbowAtrDataset {

    /** Drapeaux To the moon par bougie (valeur APRÈS mise à jour de la bougie, comme les tableaux du pine). */
    public record MoonFlags(boolean[] mode, boolean[] entry, boolean[] exit) { }

    private final long[] time;
    private final double[] high;
    private final double[] low;
    private final double[] close;
    private final double[] athRun;
    private final Map<Integer, double[]> smaCache = new ConcurrentHashMap<>();
    private final Map<Integer, double[]> atrCache = new ConcurrentHashMap<>();
    private final Map<String, MoonFlags> moonCache = new ConcurrentHashMap<>();

    public RainbowAtrDataset(long[] time, double[] high, double[] low, double[] close) {
        int n = close.length;
        if (time.length != n || high.length != n || low.length != n) {
            throw new IllegalArgumentException("séries de longueurs différentes");
        }
        this.time = time;
        this.high = high;
        this.low = low;
        this.close = close;
        this.athRun = new double[n];
        for (int i = 0; i < n; i++) {
            athRun[i] = i == 0 ? high[i] : Math.max(athRun[i - 1], high[i]);
        }
    }

    public static RainbowAtrDataset fromMarketData(List<MarketData> candles) {
        int n = candles.size();
        long[] t = new long[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            MarketData m = candles.get(i);
            t[i] = m.getTimestamp().toEpochMilli();
            h[i] = m.getHigh().doubleValue();
            l[i] = m.getLow().doubleValue();
            c[i] = m.getClose().doubleValue();
        }
        return new RainbowAtrDataset(t, h, l, c);
    }

    public int size() { return close.length; }
    public long time(int i) { return time[i]; }
    public double high(int i) { return high[i]; }
    public double low(int i) { return low[i]; }
    public double close(int i) { return close[i]; }
    public double ath(int i) { return athRun[i]; }

    /** Premier index où la SMA ET l'ATR de ces périodes sont définis. */
    public static int warmup(int smaPeriod, int atrPeriod) {
        return Math.max(smaPeriod, atrPeriod) - 1;
    }

    public double[] sma(int period) {
        return smaCache.computeIfAbsent(period, p -> {
            int n = close.length;
            double[] out = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += close[i];
                if (i >= p) { sum -= close[i - p]; }
                out[i] = i >= p - 1 ? sum / p : Double.NaN;
            }
            return out;
        });
    }

    public double[] atr(int period) {
        return atrCache.computeIfAbsent(period, p -> {
            int n = close.length;
            double[] out = new double[n];
            double[] tr = new double[n];
            for (int i = 0; i < n; i++) {
                tr[i] = i == 0 ? high[i] - low[i]
                        : Math.max(high[i] - low[i], Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
            }
            java.util.Arrays.fill(out, Double.NaN);
            if (n < p) { return out; }
            double seed = 0;
            for (int i = 0; i < p; i++) { seed += tr[i]; }
            out[p - 1] = seed / p;
            for (int i = p; i < n; i++) {
                out[i] = (out[i - 1] * (p - 1) + tr[i]) / p;
            }
            return out;
        });
    }

    /**
     * Drapeaux To the moon. Entrée : première clôture au-dessus de l'ATH (des plus hauts) de la bougie
     * précédente, mode inactif ; pic = high de la bougie d'entrée. Sortie : clôture ≤ pic × (1 - stop%).
     * Pas de test de sortie sur la bougie d'entrée. {@code moonOn=false} : aucun drapeau.
     */
    public MoonFlags moon(boolean moonOn, double trailingStopPct) {
        return moonCache.computeIfAbsent(moonOn + "|" + trailingStopPct, k -> {
            int n = close.length;
            boolean[] mode = new boolean[n];
            boolean[] entry = new boolean[n];
            boolean[] exit = new boolean[n];
            boolean active = false;
            double peak = Double.NaN;
            for (int i = 0; i < n; i++) {
                boolean newAthClose = i > 0 && close[i] > athRun[i - 1];
                if (moonOn && !active && newAthClose) {
                    active = true;
                    peak = high[i];
                    entry[i] = true;
                } else if (active) {
                    peak = Math.max(Double.isNaN(peak) ? high[i] : peak, high[i]);
                    if (close[i] <= peak * (1 - trailingStopPct / 100)) {
                        active = false;
                        exit[i] = true;
                        peak = Double.NaN;
                    }
                }
                mode[i] = active;
            }
            return new MoonFlags(mode, entry, exit);
        });
    }
}
