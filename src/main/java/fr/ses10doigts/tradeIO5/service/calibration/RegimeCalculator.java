package fr.ses10doigts.tradeIO5.service.calibration;

import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Portage Java de {@code tools/calibration/export_zones_v2.py::compute_adx}/{@code regime_segments}
 * (méthode ADX de Wilder, identique dans son principe à
 * {@link fr.ses10doigts.tradeIO5.service.tree.indicator.impl.AdxIndicator}, qui ne réutilise pas
 * cette classe car il n'expose qu'une valeur finale unique, pas la série complète nécessaire ici
 * pour découper l'historique en segments de régime).
 */
public final class RegimeCalculator {

    private RegimeCalculator() {
    }

    public static final int ADX_PERIOD = 14;
    public static final double ADX_TREND_TH = 25.0;
    public static final double ADX_RANGE_TH = 20.0;

    public enum Regime { TREND, RANGE, NEUTRAL }

    public record RegimeSegment(String start, String end, Regime regime) {
    }

    public static double[] computeAdx(List<DailyCandle> candles, int period) {
        int n = candles.size();
        double[] highs = candles.stream().mapToDouble(DailyCandle::high).toArray();
        double[] lows = candles.stream().mapToDouble(DailyCandle::low).toArray();
        double[] closes = candles.stream().mapToDouble(DailyCandle::close).toArray();

        double[] upMove = new double[n];
        double[] downMove = new double[n];
        double[] tr = new double[n];
        for (int i = 1; i < n; i++) {
            upMove[i] = highs[i] - highs[i - 1];
            downMove[i] = lows[i - 1] - lows[i];
            tr[i] = Math.max(highs[i] - lows[i],
                    Math.max(Math.abs(highs[i] - closes[i - 1]), Math.abs(lows[i] - closes[i - 1])));
        }
        double[] plusDm = new double[n];
        double[] minusDm = new double[n];
        for (int i = 0; i < n; i++) {
            plusDm[i] = (upMove[i] > downMove[i] && upMove[i] > 0) ? upMove[i] : 0;
            minusDm[i] = (downMove[i] > upMove[i] && downMove[i] > 0) ? downMove[i] : 0;
        }

        double[] trS = wilderSmooth(tr, period, n);
        double[] plusDmS = wilderSmooth(plusDm, period, n);
        double[] minusDmS = wilderSmooth(minusDm, period, n);

        double[] dx = new double[n];
        Arrays.fill(dx, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(trS[i]) || trS[i] == 0) {
                continue;
            }
            double plusDi = 100 * plusDmS[i] / trS[i];
            double minusDi = 100 * minusDmS[i] / trS[i];
            double sumDi = plusDi + minusDi;
            dx[i] = sumDi == 0 ? Double.NaN : 100 * Math.abs(plusDi - minusDi) / sumDi;
        }

        double[] adx = new double[n];
        Arrays.fill(adx, Double.NaN);
        int firstValid = period * 2;
        if (firstValid < n) {
            double sum = 0;
            int count = 0;
            for (int i = period + 1; i <= firstValid; i++) {
                if (!Double.isNaN(dx[i])) {
                    sum += dx[i];
                    count++;
                }
            }
            adx[firstValid] = count > 0 ? sum / count : Double.NaN;
            for (int i = firstValid + 1; i < n; i++) {
                double prev = adx[i - 1];
                adx[i] = Double.isNaN(dx[i]) ? prev : (prev * (period - 1) + dx[i]) / period;
            }
        }
        return adx;
    }

    private static double[] wilderSmooth(double[] x, int period, int n) {
        double[] s = new double[n];
        Arrays.fill(s, Double.NaN);
        if (n <= period) {
            return s;
        }
        double sum = 0;
        for (int i = 1; i <= period; i++) {
            sum += x[i];
        }
        s[period] = sum;
        for (int i = period + 1; i < n; i++) {
            s[i] = s[i - 1] - s[i - 1] / period + x[i];
        }
        return s;
    }

    public static List<RegimeSegment> regimeSegments(List<DailyCandle> candles, double[] adx) {
        int n = candles.size();
        Regime[] labels = new Regime[n];
        for (int i = 0; i < n; i++) {
            double a = adx[i];
            if (Double.isNaN(a)) {
                labels[i] = null;
            } else if (a >= ADX_TREND_TH) {
                labels[i] = Regime.TREND;
            } else if (a < ADX_RANGE_TH) {
                labels[i] = Regime.RANGE;
            } else {
                labels[i] = Regime.NEUTRAL;
            }
        }

        List<RegimeSegment> segments = new ArrayList<>();
        Regime curLabel = null;
        int curStart = -1;
        for (int i = 0; i < n; i++) {
            if (labels[i] != curLabel) {
                if (curLabel != null) {
                    segments.add(new RegimeSegment(candles.get(curStart).date().toString(),
                            candles.get(i - 1).date().toString(), curLabel));
                }
                curLabel = labels[i];
                curStart = i;
            }
        }
        if (curLabel != null) {
            segments.add(new RegimeSegment(candles.get(curStart).date().toString(),
                    candles.get(n - 1).date().toString(), curLabel));
        }
        return segments;
    }
}
