package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie l'adaptateur {@link SentimentShiftModulator} (étude "unification-confidence-modulator",
 * 2026-07-16, §2) : doit reproduire {@link MarketOpinionHelper#computeSentimentShiftDampening} à
 * l'identique, seulement enrobé dans le contrat {@link ConfidenceModulator}. Aucun test dédié
 * n'existait pour cette classe avant ce lot (seul {@code GlobalMarketOpinion} l'utilise).
 */
@DisplayName("SentimentShiftModulator - adaptateur de computeSentimentShiftDampening")
class SentimentShiftModulatorTest {

    private static final double BUY_THRESHOLD = 25.0;
    private static final double SELL_THRESHOLD = 75.0;
    private static final double DELTA_THRESHOLD = 15.0;

    @Test
    @DisplayName("yesterday absent => applied=false, facteur neutre 1.0 (pas d'atténuation possible)")
    void missingYesterday_notApplied_neutralFactor() {
        SentimentShiftModulator modulator = new SentimentShiftModulator(
                80.0, null, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        ModulationResult result = modulator.evaluate(null, null);

        assertFalse(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
    }

    @Test
    @DisplayName("mouvement brutal en zone extrême => applied=true, même facteur que computeSentimentShiftDampening")
    void brutalMoveInExtremeZone_appliedTrue_matchesHelper() {
        SentimentShiftModulator modulator = new SentimentShiftModulator(
                80.0, 40.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        ModulationResult result = modulator.evaluate(null, null);

        double expected = MarketOpinionHelper.computeSentimentShiftDampening(
                80.0, 40.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);
        assertTrue(result.applied());
        assertEquals(expected, result.factor(), 1e-9);
        assertTrue(result.factor() > 0.0 && result.factor() < 1.0);
    }

    @Test
    @DisplayName("mouvement stable (sous le seuil de delta) => applied=true, facteur neutre 1.0")
    void stableMove_appliedTrue_neutralFactor() {
        SentimentShiftModulator modulator = new SentimentShiftModulator(
                80.0, 78.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        ModulationResult result = modulator.evaluate(null, null);

        assertTrue(result.applied());
        assertEquals(1.0, result.factor(), 1e-9);
    }

    @Test
    @DisplayName("context/parameters ignorés : le résultat ne dépend que des valeurs passées au constructeur")
    void evaluate_ignoresContextAndParameters() {
        SentimentShiftModulator modulator = new SentimentShiftModulator(
                80.0, 40.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        ModulationResult withNulls = modulator.evaluate(null, null);
        // Passer des arguments non-null ne doit rien changer : cette implémentation ne les lit jamais.
        ModulationResult withNonNulls = modulator.evaluate(null, MarketOpinionParameters.builder().build());

        assertEquals(withNulls.factor(), withNonNulls.factor(), 1e-9);
        assertEquals(withNulls.applied(), withNonNulls.applied());
    }
}
