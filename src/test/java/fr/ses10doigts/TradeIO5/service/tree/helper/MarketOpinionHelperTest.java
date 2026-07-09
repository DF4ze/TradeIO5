package fr.ses10doigts.tradeIO5.service.tree.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarketOpinionHelper - computeSentimentShiftDampening (Fear&Greed, évolution)")
class MarketOpinionHelperTest {

    private static final double BUY_THRESHOLD = 25.0;
    private static final double SELL_THRESHOLD = 75.0;
    private static final double DELTA_THRESHOLD = 15.0;

    @Test
    @DisplayName("Zone extrême + hausse brutale => facteur d'atténuation < 1, inférieur au cas stable")
    void brutalMoveInExtremeZone_reducesDampeningFactor() {
        // now identique (80, extreme greed), mais yesterday très différent (40) vs stable (78)
        double factorBrutalRise = MarketOpinionHelper.computeSentimentShiftDampening(
                80.0, 40.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);
        double factorStable = MarketOpinionHelper.computeSentimentShiftDampening(
                80.0, 78.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        assertEquals(1.0, factorStable, 1e-9, "sous le seuil de delta, pas d'atténuation");
        assertTrue(factorBrutalRise < factorStable,
                "une hausse brutale doit être atténuée davantage qu'un mouvement stable");
        assertTrue(factorBrutalRise > 0.0 && factorBrutalRise < 1.0,
                "le facteur doit rester dans ]0,1[, jamais annulé brutalement à 0");
    }

    @Test
    @DisplayName("yesterday absent => comportement inchangé (facteur neutre 1.0)")
    void missingYesterday_fallsBackToNeutralFactor() {
        double factor = MarketOpinionHelper.computeSentimentShiftDampening(
                80.0, null, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        assertEquals(1.0, factor, 1e-9);
    }

    @Test
    @DisplayName("now hors zone extrême => facteur neutre même avec un delta important")
    void notInExtremeZone_ignoresLargeDelta() {
        // now = 50 (zone HOLD, ni <= 25 ni >= 75), delta énorme (yesterday=10 -> delta=40)
        double factor = MarketOpinionHelper.computeSentimentShiftDampening(
                50.0, 10.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD);

        assertEquals(1.0, factor, 1e-9);
    }

    @Test
    @DisplayName("delta exactement au seuil => pas encore d'atténuation")
    void deltaExactlyAtThreshold_noDampeningYet() {
        double factor = MarketOpinionHelper.computeSentimentShiftDampening(
                80.0, 65.0, BUY_THRESHOLD, SELL_THRESHOLD, DELTA_THRESHOLD); // delta = 15

        assertEquals(1.0, factor, 1e-9);
    }
}
