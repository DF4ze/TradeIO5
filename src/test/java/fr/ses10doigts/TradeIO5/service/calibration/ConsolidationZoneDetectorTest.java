package fr.ses10doigts.tradeIO5.service.calibration;

import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste {@link ConsolidationZoneDetector} sur des bougies synthétiques construites à la main (pas
 * de vraies données marché — le détecteur est un portage direct de
 * {@code tools/calibration/band_zone_test.py::technique_consolidation} /
 * {@code duration_vs_touches_analysis.py::dedup_zones}, déjà validé statistiquement côté Python ;
 * ces tests valident uniquement que le portage Java respecte la même logique, pas que la technique
 * a une valeur prédictive — cf. docs/calibration-rejection-zone.md pour ce second point).
 */
class ConsolidationZoneDetectorTest {

    private static DailyCandle candle(int dayOffset, double open, double high, double low, double close,
            double volume) {
        return new DailyCandle(LocalDate.of(2024, 1, 1).plusDays(dayOffset), open, high, low, close, volume);
    }

    @Test
    @DisplayName("percentile() correspond a numpy.percentile (interpolation lineaire)")
    void percentile_matchesNumpyLinearInterpolation() {
        double[] values = {10, 20, 30, 40, 50};
        // np.percentile([10,20,30,40,50], 50) == 30.0
        assertEquals(30.0, ConsolidationZoneDetector.percentile(values, 0, values.length, 50), 1e-9);
        // np.percentile([10,20,30,40,50], 10) == 14.0
        assertEquals(14.0, ConsolidationZoneDetector.percentile(values, 0, values.length, 10), 1e-9);
        // np.percentile([10,20,30,40,50], 90) == 46.0
        assertEquals(46.0, ConsolidationZoneDetector.percentile(values, 0, values.length, 90), 1e-9);
    }

    @Test
    @DisplayName("wilderAtr() : NaN pendant le warmup, positif ensuite sur des bougies avec range")
    void wilderAtr_warmupThenPositive() {
        List<DailyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candles.add(candle(i, 100, 102, 98, 100 + (i % 2), 10));
        }
        double[] atr = ConsolidationZoneDetector.wilderAtr(candles, 14);
        for (int i = 0; i < 14; i++) {
            assertTrue(Double.isNaN(atr[i]), "atr[" + i + "] devrait etre NaN avant la periode");
        }
        assertFalse(Double.isNaN(atr[14]));
        assertTrue(atr[14] > 0);
        assertTrue(atr[29] > 0);
    }

    @Test
    @DisplayName("detectConsolidationBands() detecte une bande sur un range suivi d'une cassure")
    void detectConsolidationBands_findsRangeBreakout() {
        List<DailyCandle> candles = new ArrayList<>();
        // Bruit initial pour amorcer l'ATR (periode 14) avant le range a detecter.
        for (int i = 0; i < 20; i++) {
            candles.add(candle(i, 100, 101, 99, 100, 10));
        }
        // Range serre (10 bougies) : plancher a detecter par la technique consolidation.
        int rangeStart = candles.size();
        for (int i = 0; i < 15; i++) {
            double c = 100 + (i % 2 == 0 ? 0.3 : -0.3);
            candles.add(candle(rangeStart + i, c, c + 0.5, c - 0.5, c, 10));
        }
        // Cassure nette vers le haut, confirmee sur plusieurs bougies.
        int breakoutStart = candles.size();
        for (int i = 0; i < 5; i++) {
            double c = 110 + i;
            candles.add(candle(breakoutStart + i, c, c + 1, c - 1, c, 10));
        }

        double[] atr = ConsolidationZoneDetector.wilderAtr(candles, 14);
        List<ConsolidationZoneDetector.Band> bands = ConsolidationZoneDetector.detectConsolidationBands(
                candles, candles.size(), atr, 10, 14, 4, 10, 2.5, 3, 0.5);

        assertFalse(bands.isEmpty(), "une bande devrait etre detectee sur le range avant la cassure");
        ConsolidationZoneDetector.Band band = bands.getFirst();
        assertTrue(band.bandLow() < 101 && band.bandHigh() > 99,
                "la bande detectee devrait englober le range ~99.5-100.5, obtenu [" + band.bandLow() + ","
                        + band.bandHigh() + "]");
    }

    @Test
    @DisplayName("dedupZones() fusionne deux zones proches en prix et en temps")
    void dedupZones_mergesOverlappingZones() {
        List<DailyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candles.add(candle(i, 100, 101, 99, 100, 10));
        }
        double[] atr = ConsolidationZoneDetector.wilderAtr(candles, 14);

        ConsolidationZoneDetector.Band a = new ConsolidationZoneDetector.Band(99.0, 101.0, 10, 25);
        ConsolidationZoneDetector.Band b = new ConsolidationZoneDetector.Band(99.5, 101.5, 8, 27);
        ConsolidationZoneDetector.Band farAway = new ConsolidationZoneDetector.Band(150.0, 152.0, 5, 30);

        List<ConsolidationZoneDetector.Band> deduped = ConsolidationZoneDetector.dedupZones(
                List.of(a, b, farAway), candles, atr, 1.0, 15);

        assertEquals(2, deduped.size(), "a et b (proches en prix/temps) doivent fusionner ; farAway reste separee");
    }

    @Test
    @DisplayName("dedupZones() ne fusionne pas des zones eloignees en prix")
    void dedupZones_keepsDistantZonesSeparate() {
        List<DailyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candles.add(candle(i, 100, 101, 99, 100, 10));
        }
        double[] atr = ConsolidationZoneDetector.wilderAtr(candles, 14);

        ConsolidationZoneDetector.Band a = new ConsolidationZoneDetector.Band(50.0, 51.0, 10, 25);
        ConsolidationZoneDetector.Band b = new ConsolidationZoneDetector.Band(150.0, 151.0, 8, 26);

        List<ConsolidationZoneDetector.Band> deduped = ConsolidationZoneDetector.dedupZones(
                List.of(a, b), candles, atr, 1.0, 15);

        assertEquals(2, deduped.size());
    }
}
