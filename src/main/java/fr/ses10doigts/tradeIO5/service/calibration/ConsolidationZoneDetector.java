package fr.ses10doigts.tradeIO5.service.calibration;

import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Portage Java fidèle de la technique "consolidation" (idée de Clem, 2026-07-09) et de son
 * pipeline de déduplication/métriques, tels que définis dans les scripts de calibration Python :
 * {@code tools/calibration/band_zone_test.py::technique_consolidation},
 * {@code tools/calibration/duration_vs_touches_analysis.py::dedup_zones}, et
 * {@code tools/calibration/export_zones_v2.py} (boucle de calcul des métriques par zone). C'est la
 * SEULE des 6 techniques testées lors de la calibration (cf. docs/calibration-rejection-zone.md)
 * qui montre un pattern statistique net et interprétable (significative en range, s'effondre en
 * tendance baissière forte) — d'où le choix de la porter en Java plutôt que
 * {@code RejectionZoneIndicator} (technique "rejection", wick-based, testée mais pas concluante).
 * <p>
 * Ce détecteur reste un utilitaire de calcul pur (aucune dépendance Spring, aucun état) : il est
 * volontairement séparé du pipeline {@code Indicator}/{@code Strategy}/{@code Opinion} de l'app —
 * le verdict de calibration ("aucun edge robuste en walk-forward, ne pas brancher en prod",
 * cf. docs/calibration-rejection-zone.md) reste valable pour toute utilisation comme signal de
 * trading automatisé. Cette classe sert uniquement l'usage de visualisation/diagnostic manuel
 * (zone_view_v2.html via {@code CalibrationController}).
 */
public final class ConsolidationZoneDetector {

    private ConsolidationZoneDetector() {
    }

    /** Résultat brut de détection : une bande de prix, sans les métriques de réaction (calculées séparément). */
    public record Band(double bandLow, double bandHigh, int touches, int endIdx) {
    }

    /** Un événement de "touche" de bande : la bougie {@code idx} entre dans la bande depuis l'extérieur. */
    record TouchEvent(int idx, boolean fromAbove) {
    }

    public record TouchView(String date, double price, double reaction) {
    }

    public record ZoneView(double bandLow, double bandHigh, String anchorDate, double durationDays,
                            double volumeStrength, int nTouches, double meanReaction,
                            TouchView firstTouch, List<TouchView> touches) {
    }

    // ---- Wilder ATR (identique à AtrIndicator/wilder_atr côté Python, mais exposé en série complète) ----

    public static double[] wilderAtr(List<DailyCandle> candles, int period) {
        int n = candles.size();
        double[] atr = new double[n];
        Arrays.fill(atr, Double.NaN);
        if (n < period + 1) {
            return atr;
        }
        double[] tr = new double[n];
        for (int i = 1; i < n; i++) {
            DailyCandle c = candles.get(i);
            DailyCandle p = candles.get(i - 1);
            tr[i] = Math.max(c.high() - c.low(),
                    Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close())));
        }
        double sum = 0;
        for (int i = 1; i <= period; i++) {
            sum += tr[i];
        }
        atr[period] = sum / period;
        for (int i = period + 1; i < n; i++) {
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period;
        }
        return atr;
    }

    // ---- Percentile "linear" (même méthode que numpy.percentile par défaut) ----

    static double percentile(double[] values, int fromIncl, int toExcl, double pct) {
        double[] slice = Arrays.copyOfRange(values, fromIncl, toExcl);
        Arrays.sort(slice);
        int n = slice.length;
        if (n == 0) {
            return Double.NaN;
        }
        if (n == 1) {
            return slice[0];
        }
        double rank = (pct / 100.0) * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) {
            return slice[lo];
        }
        double frac = rank - lo;
        return slice[lo] + (slice[hi] - slice[lo]) * frac;
    }

    // ---- Technique "consolidation" : détection des bandes ----

    public static List<Band> detectConsolidationBands(List<DailyCandle> candles, int lookback, double[] atrSeries,
            int minWindow, int maxWindow, int windowStep, int trimPct, double maxRangeAtrMult,
            int breakoutConfirmCandles, double breakoutMarginAtrMult) {
        int n = candles.size();
        if (n < lookback) {
            lookback = n;
        }
        int start = Math.max(0, n - lookback);
        double[] highs = candles.stream().mapToDouble(DailyCandle::high).toArray();
        double[] lows = candles.stream().mapToDouble(DailyCandle::low).toArray();
        double[] closes = candles.stream().mapToDouble(DailyCandle::close).toArray();

        List<Integer> windowSizes = new ArrayList<>();
        for (int w = minWindow; w <= maxWindow; w += windowStep) {
            windowSizes.add(w);
        }

        List<Band> bands = new ArrayList<>();
        int i = start;
        while (i < n - minWindow) {
            int probeEnd = i + minWindow;
            if (probeEnd >= n) {
                break;
            }
            double atrProbe = atrSeries[probeEnd];
            if (Double.isNaN(atrProbe) || atrProbe <= 0) {
                i++;
                continue;
            }
            double probeHigh = percentile(highs, i, probeEnd, 100 - trimPct);
            double probeLow = percentile(lows, i, probeEnd, trimPct);
            if (probeHigh - probeLow > maxRangeAtrMult * atrProbe * 1.3) {
                i++;
                continue;
            }

            boolean found = false;
            int foundEnd = -1;
            double bestBandLow = 0;
            double bestBandHigh = 0;
            double bestAtrHere = 0;
            for (int w : windowSizes) {
                int end = i + w;
                if (end >= n) {
                    break;
                }
                double atrHere = atrSeries[end];
                if (Double.isNaN(atrHere) || atrHere <= 0) {
                    continue;
                }
                double bandHigh = percentile(highs, i, end, 100 - trimPct);
                double bandLow = percentile(lows, i, end, trimPct);
                if (bandHigh - bandLow > maxRangeAtrMult * atrHere) {
                    break;
                }
                found = true;
                foundEnd = end;
                bestBandLow = bandLow;
                bestBandHigh = bandHigh;
                bestAtrHere = atrHere;
            }
            if (!found) {
                i++;
                continue;
            }

            boolean broke = false;
            for (int k = foundEnd; k < Math.min(foundEnd + breakoutConfirmCandles, n); k++) {
                double c = closes[k];
                if (c > bestBandHigh + breakoutMarginAtrMult * bestAtrHere
                        || c < bestBandLow - breakoutMarginAtrMult * bestAtrHere) {
                    broke = true;
                    break;
                }
            }
            if (broke) {
                bands.add(new Band(bestBandLow, bestBandHigh, foundEnd - i, foundEnd));
                i = foundEnd + breakoutConfirmCandles;
            } else {
                i++;
            }
        }
        return bands;
    }

    // ---- Déduplication (zones proches en prix ET en temps = même détection retrouvée 2x) ----

    private record DatedZone(Band z, java.time.LocalDate date, double atr) {
    }

    public static List<Band> dedupZones(List<Band> zones, List<DailyCandle> candles, double[] atrSeries,
            double maxGapAtrMult, int maxCandlesApartDays) {
        int n = candles.size();
        List<DatedZone> dated = new ArrayList<>();
        for (Band z : zones) {
            int idx = Math.min(z.endIdx(), n - 1);
            java.time.LocalDate date = candles.get(idx).date();
            double atrHere = atrSeries[idx];
            if (Double.isNaN(atrHere) || atrHere <= 0) {
                atrHere = 1.0;
            }
            dated.add(new DatedZone(z, date, atrHere));
        }
        dated.sort(Comparator.<DatedZone, java.time.LocalDate>comparing(DatedZone::date)
                .thenComparingDouble(d -> d.z().bandLow()));

        List<DatedZone> merged = new ArrayList<>();
        for (DatedZone d : dated) {
            int matchIdx = -1;
            for (int mi = 0; mi < merged.size(); mi++) {
                DatedZone m = merged.get(mi);
                double gap = m.atr() * maxGapAtrMult;
                long daysApart = Math.abs(ChronoUnit.DAYS.between(d.date(), m.date()));
                if (daysApart <= maxCandlesApartDays
                        && d.z().bandLow() <= m.z().bandHigh() + gap
                        && d.z().bandHigh() >= m.z().bandLow() - gap) {
                    matchIdx = mi;
                    break;
                }
            }
            if (matchIdx >= 0) {
                DatedZone m = merged.get(matchIdx);
                double newLow = Math.min(m.z().bandLow(), d.z().bandLow());
                double newHigh = Math.max(m.z().bandHigh(), d.z().bandHigh());
                int newTouches = Math.max(m.z().touches(), d.z().touches());
                int newEndIdx = Math.max(m.z().endIdx(), d.z().endIdx());
                java.time.LocalDate newDate = d.date().isAfter(m.date()) ? d.date() : m.date();
                merged.set(matchIdx, new DatedZone(new Band(newLow, newHigh, newTouches, newEndIdx), newDate, m.atr()));
            } else {
                merged.add(d);
            }
        }
        return merged.stream().map(DatedZone::z).toList();
    }

    // ---- Événements de "touche" (entrée dans la bande depuis l'extérieur) ----

    static List<TouchEvent> bandTouchEvents(double bandLow, double bandHigh, double[] highs, double[] lows,
            double[] closes, int minIndex) {
        int n = closes.length;
        boolean[] inside = new boolean[n];
        for (int i = 0; i < n; i++) {
            inside[i] = lows[i] <= bandHigh && highs[i] >= bandLow;
        }
        int lo = Math.max(minIndex, 1);
        List<TouchEvent> events = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            if (i < lo) {
                continue;
            }
            boolean onset = inside[i] && !inside[i - 1];
            if (!onset) {
                continue;
            }
            double prevClose = closes[i - 1];
            boolean fromAbove = prevClose > bandHigh;
            boolean fromBelow = prevClose < bandLow;
            if (fromAbove || fromBelow) {
                events.add(new TouchEvent(i, fromAbove));
            }
        }
        return events;
    }

    private static double mean(double[] arr, int fromIncl, int toExcl) {
        if (toExcl <= fromIncl) {
            return Double.NaN;
        }
        double sum = 0;
        int count = 0;
        for (int i = fromIncl; i < toExcl; i++) {
            sum += arr[i];
            count++;
        }
        return sum / count;
    }

    // ---- Métriques par zone (durée, volume causal, touches + réaction ATR-normalisée) ----

    public static List<ZoneView> buildZoneViews(List<Band> zones, List<DailyCandle> candles, double[] atrSeries,
            int horizon) {
        int n = candles.size();
        double[] highs = candles.stream().mapToDouble(DailyCandle::high).toArray();
        double[] lows = candles.stream().mapToDouble(DailyCandle::low).toArray();
        double[] closes = candles.stream().mapToDouble(DailyCandle::close).toArray();
        double[] volumes = candles.stream().mapToDouble(DailyCandle::volume).toArray();

        List<ZoneView> out = new ArrayList<>();
        for (Band z : zones) {
            int startIdx = Math.max(0, z.endIdx() - z.touches());
            int endIdx = Math.min(z.endIdx(), n - 1);
            if (startIdx <= 0) {
                continue;
            }
            double formationVol = mean(volumes, startIdx, endIdx);
            double baselineVol = mean(volumes, 0, startIdx);
            if (Double.isNaN(formationVol) || baselineVol <= 0) {
                continue;
            }
            double volumeStrength = formationVol / baselineVol;

            List<TouchEvent> events = bandTouchEvents(z.bandLow(), z.bandHigh(), highs, lows, closes, 0);
            List<TouchView> touches = new ArrayList<>();
            for (TouchEvent e : events) {
                int j = e.idx() + horizon;
                if (j >= n) {
                    continue;
                }
                double atrHere = atrSeries[e.idx()];
                if (Double.isNaN(atrHere) || atrHere <= 0) {
                    continue;
                }
                double closeH = closes[j];
                double edge = e.fromAbove() ? z.bandHigh() : z.bandLow();
                double raw = e.fromAbove() ? (closeH - z.bandHigh()) : (z.bandLow() - closeH);
                double reaction = raw / atrHere;
                touches.add(new TouchView(candles.get(e.idx()).date().toString(), edge, reaction));
            }
            if (touches.isEmpty()) {
                continue;
            }
            double meanReaction = touches.stream().mapToDouble(TouchView::reaction).average().orElse(0);
            out.add(new ZoneView(z.bandLow(), z.bandHigh(), candles.get(endIdx).date().toString(),
                    z.touches(), volumeStrength, touches.size(), meanReaction, touches.getFirst(), touches));
        }
        return out;
    }
}
