package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.service.modulation.ExposureModulator.Band;
import fr.ses10doigts.tradeIO5.service.modulation.ExposureModulator.Factors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ExposureModulator")
class ExposureModulatorTest {

    private static final double EPS = 1e-4;
    private static final Band BAND_30_90 = new Band(30, 90, 25, 2);

    @Test
    @DisplayName("Exemple de la spec : bande 30–90, m 25 %, exposition 80 % => achat ×0,74, vente ×1,26")
    void specExample() {
        Factors f = ExposureModulator.factors(80, BAND_30_90);
        assertEquals(0.7407, f.buyFactor(), EPS);
        assertEquals(1.2593, f.sellFactor(), EPS);
    }

    @Test
    @DisplayName("Zone libre au centre : aucune modulation, jusqu'à max − m (75 %) et depuis min + m (45 %)")
    void centerIsFree() {
        for (double exposure : new double[]{45, 60, 75}) {
            Factors f = ExposureModulator.factors(exposure, BAND_30_90);
            assertEquals(1.0, f.buyFactor(), EPS);
            assertEquals(1.0, f.sellFactor(), EPS);
        }
    }

    @Test
    @DisplayName("89,99 % pour max 90 % : achat ≈ 0 et vente ≈ ×2 ; au-delà de la borne, saturé")
    void nearAndBeyondUpperBound() {
        Factors near = ExposureModulator.factors(89.99, BAND_30_90);
        assertEquals(0.0, near.buyFactor(), 1e-4);
        assertEquals(2.0, near.sellFactor(), 1e-4);
        Factors beyond = ExposureModulator.factors(95, BAND_30_90);
        assertEquals(0.0, beyond.buyFactor(), EPS);
        assertEquals(2.0, beyond.sellFactor(), EPS);
    }

    @Test
    @DisplayName("Marge basse symétrique : vente réduite, achat accentué")
    void lowerMarginIsSymmetric() {
        Factors f = ExposureModulator.factors(40, BAND_30_90);
        assertEquals(0.7407, f.sellFactor(), EPS);
        assertEquals(1.2593, f.buyFactor(), EPS);
        Factors beyond = ExposureModulator.factors(10, BAND_30_90);
        assertEquals(0.0, beyond.sellFactor(), EPS);
        assertEquals(2.0, beyond.buyFactor(), EPS);
    }

    @Test
    @DisplayName("Bande neutre [0, 100] : jamais de modulation, quelle que soit l'exposition")
    void neutralBand() {
        Band neutral = new Band(0, 100, 25, 2);
        for (double exposure : new double[]{0, 1, 50, 99, 100}) {
            Factors f = ExposureModulator.factors(exposure, neutral);
            assertEquals(1.0, f.buyFactor(), EPS);
            assertEquals(1.0, f.sellFactor(), EPS);
        }
    }

    @Test
    @DisplayName("min = 0 : pas de marge basse ; max = 100 : pas de marge haute")
    void oneSidedBands() {
        Band noLow = new Band(0, 90, 25, 2);
        assertEquals(1.0, ExposureModulator.factors(0, noLow).buyFactor(), EPS);
        assertEquals(1.0, ExposureModulator.factors(0, noLow).sellFactor(), EPS);
        assertEquals(0.0, ExposureModulator.factors(90, noLow).buyFactor(), EPS);
        Band noHigh = new Band(30, 100, 25, 2);
        assertEquals(1.0, ExposureModulator.factors(100, noHigh).buyFactor(), EPS);
        assertEquals(1.0, ExposureModulator.factors(100, noHigh).sellFactor(), EPS);
        assertEquals(0.0, ExposureModulator.factors(30, noHigh).sellFactor(), EPS);
    }

    @Test
    @DisplayName("G variable : accentuation maximale de la vente")
    void boostIsConfigurable() {
        assertEquals(3.0, ExposureModulator.factors(90, new Band(30, 90, 25, 3)).sellFactor(), EPS);
        assertEquals(1.0, ExposureModulator.factors(90, new Band(30, 90, 25, 1)).sellFactor(), EPS);
    }

    @Test
    @DisplayName("Courbe continue : les facteurs ne sautent jamais entre deux expositions voisines")
    void curveIsContinuous() {
        double previous = ExposureModulator.factors(60, BAND_30_90).buyFactor();
        for (double exposure = 60; exposure <= 95; exposure += 0.01) {
            double current = ExposureModulator.factors(exposure, BAND_30_90).buyFactor();
            assertTrue(Math.abs(current - previous) < 0.01, "saut à " + exposure);
            assertTrue(current <= previous + 1e-12, "non décroissant à " + exposure);
            previous = current;
        }
    }

    @Test
    @DisplayName("Plafonds durs : achat ≤ max·V − P, vente ≤ P − min·V, plancher 0, illimités si bande neutre")
    void hardCaps() {
        assertEquals(100.0, ExposureModulator.buyCapUsd(800, 1000, BAND_30_90), 1e-9);
        assertEquals(500.0, ExposureModulator.sellCapUsd(800, 1000, BAND_30_90), 1e-9);
        assertEquals(0.0, ExposureModulator.buyCapUsd(950, 1000, BAND_30_90), 1e-9);
        assertEquals(0.0, ExposureModulator.sellCapUsd(200, 1000, BAND_30_90), 1e-9);
        Band neutral = new Band(0, 100, 25, 2);
        assertEquals(Double.POSITIVE_INFINITY, ExposureModulator.buyCapUsd(800, 1000, neutral));
        assertEquals(Double.POSITIVE_INFINITY, ExposureModulator.sellCapUsd(800, 1000, neutral));
    }
}
