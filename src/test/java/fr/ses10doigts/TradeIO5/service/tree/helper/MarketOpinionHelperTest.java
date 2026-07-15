package fr.ses10doigts.tradeIO5.service.tree.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    @Nested
    @DisplayName("normalizeChangeScore (étude nouvelles-opinions §2.2)")
    class NormalizeChangeScoreTest {

        @Test
        @DisplayName("interpolation linéaire dans [-scale, scale], saturée à ±1 au-delà")
        void interpolatesThenSaturates() {
            assertEquals(0.5, MarketOpinionHelper.normalizeChangeScore(0.005, 0.01), 1e-9);
            assertEquals(-0.5, MarketOpinionHelper.normalizeChangeScore(-0.005, 0.01), 1e-9);
            assertEquals(1.0, MarketOpinionHelper.normalizeChangeScore(0.05, 0.01), 1e-9);
            assertEquals(-1.0, MarketOpinionHelper.normalizeChangeScore(-0.05, 0.01), 1e-9);
            assertEquals(0.0, MarketOpinionHelper.normalizeChangeScore(0.0, 0.01), 1e-9);
        }

        @Test
        @DisplayName("scale nulle/négative => 0.0, jamais de division par zéro")
        void nonPositiveScale_returnsZero() {
            assertEquals(0.0, MarketOpinionHelper.normalizeChangeScore(0.02, 0.0), 1e-9);
            assertEquals(0.0, MarketOpinionHelper.normalizeChangeScore(0.02, -0.01), 1e-9);
        }
    }

    @Nested
    @DisplayName("computeStablecoinScore (étude nouvelles-opinions §3)")
    class ComputeStablecoinScoreTest {

        @Test
        @DisplayName("croissance hebdomadaire positive => score positif proportionnel")
        void positiveGrowth_positiveScore() {
            // +1.5% sur une échelle de 3% => 0.5
            double score = MarketOpinionHelper.computeStablecoinScore(101.5, 100.0, 0.03);
            assertEquals(0.5, score, 1e-9);
        }

        @Test
        @DisplayName("totalPrevWeek absent/nul => score neutre 0.0")
        void missingOrZeroPrevWeek_returnsZero() {
            assertEquals(0.0, MarketOpinionHelper.computeStablecoinScore(101.5, null, 0.03), 1e-9);
            assertEquals(0.0, MarketOpinionHelper.computeStablecoinScore(101.5, 0.0, 0.03), 1e-9);
        }
    }

    @Nested
    @DisplayName("computeStalenessDampening (étude nouvelles-opinions §2.3)")
    class ComputeStalenessDampeningTest {

        @Test
        @DisplayName("valeur fraîche (sous le seuil) => facteur neutre 1.0")
        void freshValue_noDampening() {
            Instant now = Instant.parse("2026-07-15T12:00:00Z");
            long lastTradeEpoch = now.minusSeconds(3600).getEpochSecond(); // 1h

            double factor = MarketOpinionHelper.computeStalenessDampening(lastTradeEpoch, now, 18.0);

            assertEquals(1.0, factor, 1e-9);
        }

        @Test
        @DisplayName("valeur figée depuis longtemps (week-end) => facteur < 1, jamais annulé brutalement")
        void staleValue_dampenedContinuously() {
            Instant now = Instant.parse("2026-07-13T12:00:00Z"); // dimanche
            long lastTradeEpoch = now.minusSeconds(60L * 60 * 40).getEpochSecond(); // 40h (vendredi soir)

            double factor = MarketOpinionHelper.computeStalenessDampening(lastTradeEpoch, now, 18.0);

            assertTrue(factor > 0.0 && factor < 1.0);
            assertEquals(18.0 / 40.0, factor, 1e-6);
        }

        @Test
        @DisplayName("timestamp absent => comportement inchangé (facteur neutre 1.0)")
        void missingTimestamp_fallsBackToNeutralFactor() {
            double factor = MarketOpinionHelper.computeStalenessDampening(null, Instant.now(), 18.0);
            assertEquals(1.0, factor, 1e-9);
        }
    }

    /**
     * Introduit le 2026-07-15 pour résoudre la dette partagée documentée dans
     * {@code MovementQualificationStrategy}/{@code OrderFlowStrategy} : ces Strategies
     * {@code CONFIDENCE_MODULATOR} qualifient la fiabilité d'un mouvement plutôt que de voter sur
     * sa direction ; leur score doit atténuer la confidence d'une Opinion, jamais l'amplifier ni
     * l'annuler brutalement.
     */
    @Nested
    @DisplayName("computeConfidenceModulationFactor (StrategyType.CONFIDENCE_MODULATOR)")
    class ComputeConfidenceModulationFactorTest {

        @Test
        @DisplayName("score positif (conviction confirmée) => facteur neutre 1.0, jamais d'amplification")
        void positiveScore_neutralFactor() {
            assertEquals(1.0, MarketOpinionHelper.computeConfidenceModulationFactor(1.0), 1e-9);
            assertEquals(1.0, MarketOpinionHelper.computeConfidenceModulationFactor(0.3), 1e-9);
        }

        @Test
        @DisplayName("score neutre (aucun pattern détecté) => facteur neutre 1.0")
        void zeroScore_neutralFactor() {
            assertEquals(1.0, MarketOpinionHelper.computeConfidenceModulationFactor(0.0), 1e-9);
        }

        @Test
        @DisplayName("score négatif => atténuation continue, jamais 0 brutal, plancher à 0.5 au score minimal -1.0")
        void negativeScore_continuousAttenuation_neverZero() {
            double factorMild = MarketOpinionHelper.computeConfidenceModulationFactor(-0.2);
            double factorSevere = MarketOpinionHelper.computeConfidenceModulationFactor(-0.8);
            double factorMax = MarketOpinionHelper.computeConfidenceModulationFactor(-1.0);

            assertTrue(factorMild < 1.0 && factorMild > factorSevere,
                    "un score plus négatif doit atténuer davantage : " + factorMild + " vs " + factorSevere);
            assertTrue(factorSevere > factorMax);
            assertEquals(0.5, factorMax, 1e-9, "score -1.0 (fragilité maximale) => facteur plancher 0.5, jamais 0");
            assertTrue(factorMax > 0.0, "jamais un 0 brutal, même au score le plus négatif possible");
        }

        @Test
        @DisplayName("score hors [-1,1] => clampé avant conversion")
        void outOfRangeScore_isClamped() {
            assertEquals(1.0, MarketOpinionHelper.computeConfidenceModulationFactor(5.0), 1e-9);
            assertEquals(0.5, MarketOpinionHelper.computeConfidenceModulationFactor(-5.0), 1e-9);
        }
    }
}
