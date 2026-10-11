package fr.ses10doigts.tradeIO5.service.tree.trend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static fr.ses10doigts.tradeIO5.service.tree.trend.RegressiveTrendScoreCalculator.WindowInput;
import static fr.ses10doigts.tradeIO5.service.tree.trend.RegressiveTrendScoreCalculator.computeScore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires de {@link RegressiveTrendScoreCalculator} — formule extraite de
 * {@code RegressiveTrendStrategy#evaluate} pour être mutualisée avec {@link TrendAnalyzer} (Étape 8
 * de la roadmap Trend unifié, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §2.1/§5).
 * Les 3 fenêtres sont ici des {@link WindowInput} synthétiques (pas de calcul de régression réel) :
 * ce fichier couvre exclusivement la formule de combinaison, pas {@link LinearRegressionCalculator}
 * (déjà testé séparément).
 */
@DisplayName("RegressiveTrendScoreCalculator")
class RegressiveTrendScoreCalculatorTest {

    private static final double DELTA = 1e-9;
    private static final double SLOPE_SCALE_FACTOR = 400.0;
    private static final double EQUAL_WEIGHT = 1.0 / 3.0;

    @Test
    @DisplayName("3 fenêtres alignées à la hausse, r2=1, poids égaux, sans alignement -> score = tanh(slope*scale)")
    void alignedPositiveWindows_scoreMatchesUnweightedTanh() {
        WindowInput window = new WindowInput(0.001, 1.0);

        double score = computeScore(window, window, window, SLOPE_SCALE_FACTOR,
                EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);

        double expected = Math.tanh(0.001 * SLOPE_SCALE_FACTOR);
        assertEquals(expected, score, DELTA);
        assertTrue(score > 0);
    }

    @Test
    @DisplayName("Symétrique : 3 fenêtres alignées à la baisse -> score négatif de même magnitude")
    void alignedNegativeWindows_scoreIsNegativeSymmetric() {
        WindowInput up = new WindowInput(0.001, 1.0);
        WindowInput down = new WindowInput(-0.001, 1.0);

        double scoreUp = computeScore(up, up, up, SLOPE_SCALE_FACTOR, EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);
        double scoreDown = computeScore(down, down, down, SLOPE_SCALE_FACTOR, EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);

        assertEquals(-scoreUp, scoreDown, DELTA);
    }

    @Test
    @DisplayName("r2=0 sur les 3 fenêtres (marché plat) -> score = 0 quelle que soit la pente")
    void zeroR2OnAllWindows_scoreIsZero() {
        WindowInput flat = new WindowInput(0.05, 0.0);

        double score = computeScore(flat, flat, flat, SLOPE_SCALE_FACTOR, EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);

        assertEquals(0.0, score, DELTA);
    }

    @Test
    @DisplayName("Poids concentré sur une fenêtre -> le score suit surtout cette fenêtre")
    void weightConcentratedOnOneWindow_scoreFollowsThatWindow() {
        WindowInput shortWindow = new WindowInput(0.01, 1.0);   // fortement haussier
        WindowInput mediumWindow = new WindowInput(-0.01, 1.0); // fortement baissier
        WindowInput longWindow = new WindowInput(-0.01, 1.0);   // fortement baissier

        // Tout le poids sur la fenêtre courte (haussière) -> score positif malgré les 2 autres négatives.
        double scoreShortDominant = computeScore(shortWindow, mediumWindow, longWindow, SLOPE_SCALE_FACTOR,
                1.0, 0.0, 0.0, false);
        assertTrue(scoreShortDominant > 0, "expected positive score, got " + scoreShortDominant);

        // Tout le poids sur les fenêtres baissières -> score négatif.
        double scoreLongDominant = computeScore(shortWindow, mediumWindow, longWindow, SLOPE_SCALE_FACTOR,
                0.0, 0.0, 1.0, false);
        assertTrue(scoreLongDominant < 0, "expected negative score, got " + scoreLongDominant);
    }

    @Test
    @DisplayName("Alignement activé : fenêtres en désaccord -> score atténué par rapport à alignement désactivé")
    void alignmentEnabled_dampensScoreWhenWindowsDisagree() {
        WindowInput up = new WindowInput(0.005, 1.0);
        WindowInput down = new WindowInput(-0.005, 1.0);

        double scoreWithoutAlignment = computeScore(up, up, down, SLOPE_SCALE_FACTOR,
                EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);
        double scoreWithAlignment = computeScore(up, up, down, SLOPE_SCALE_FACTOR,
                EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, true);

        assertTrue(Math.abs(scoreWithAlignment) < Math.abs(scoreWithoutAlignment),
                "expected |withAlignment| < |withoutAlignment| ; withAlignment=" + scoreWithAlignment
                        + " withoutAlignment=" + scoreWithoutAlignment);
    }

    @Test
    @DisplayName("Alignement activé et 3 fenêtres alignées -> aucune atténuation (facteur 1.0)")
    void alignmentEnabled_noDampeningWhenWindowsAgree() {
        WindowInput window = new WindowInput(0.001, 1.0);

        double scoreWithoutAlignment = computeScore(window, window, window, SLOPE_SCALE_FACTOR,
                EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);
        double scoreWithAlignment = computeScore(window, window, window, SLOPE_SCALE_FACTOR,
                EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, true);

        assertEquals(scoreWithoutAlignment, scoreWithAlignment, DELTA);
    }

    @Test
    @DisplayName("Score toujours clampé dans [-1,1], même avec un slopeScaleFactor/normalizedSlope extrêmes")
    void scoreIsAlwaysClamped() {
        WindowInput extreme = new WindowInput(10.0, 1.0);

        double score = computeScore(extreme, extreme, extreme, 1_000_000.0,
                EQUAL_WEIGHT, EQUAL_WEIGHT, EQUAL_WEIGHT, false);

        assertTrue(score <= 1.0 && score >= -1.0);
        assertEquals(1.0, score, DELTA);
    }
}
