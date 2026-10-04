package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests unitaires de {@link RegressionHysteresisCalculator} sur des séries de scores synthétiques
 * (Étape 8 de la roadmap Trend unifié, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §2.2/§5) :
 * entrée, maintien sous la barrière, sortie à 0, bascule directe UP → DOWN, warmup insuffisant.
 * {@code ENTER_THRESHOLD} vaut 1/6 ({@link MarketOpinionHelper#BARRIER} réutilisé), {@code EXIT_THRESHOLD}
 * vaut 0.
 */
@DisplayName("RegressionHysteresisCalculator")
class RegressionHysteresisCalculatorTest {

    private static final double ENTER = RegressionHysteresisCalculator.ENTER_THRESHOLD;

    /** Complète en tête avec des scores neutres (0.0) jusqu'à atteindre MIN_SCORES - tail.length. */
    private static List<Double> withWarmup(double... tail) {
        List<Double> scores = new ArrayList<>();
        int warmup = RegressionHysteresisCalculator.MIN_SCORES - tail.length;
        for (int i = 0; i < warmup; i++) {
            scores.add(0.0);
        }
        for (double v : tail) {
            scores.add(v);
        }
        return scores;
    }

    @Test
    @DisplayName("Warmup insuffisant (< MIN_SCORES) -> IllegalArgumentException")
    void insufficientWarmupThrows() {
        List<Double> scores = List.of(0.0, 0.1, 0.2);
        assertThrows(IllegalArgumentException.class, () -> RegressionHysteresisCalculator.computeTimeline(scores));
    }

    @Test
    @DisplayName("Liste null -> IllegalArgumentException")
    void nullScoresThrows() {
        assertThrows(IllegalArgumentException.class, () -> RegressionHysteresisCalculator.computeTimeline(null));
    }

    @Test
    @DisplayName("Score qui franchit ENTER depuis RANGE -> entrée en UP dès la bougie de franchissement")
    void crossingEnterFromRange_entersUp() {
        List<Double> scores = withWarmup(0.0, 0.05, ENTER + 0.01);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        assertEquals(TrendRegime.RANGE, regimes.get(regimes.size() - 3));
        assertEquals(TrendRegime.RANGE, regimes.get(regimes.size() - 2));
        assertEquals(TrendRegime.UP, regimes.get(regimes.size() - 1));
    }

    @Test
    @DisplayName("Score symétrique qui franchit -ENTER depuis RANGE -> entrée en DOWN")
    void crossingNegativeEnterFromRange_entersDown() {
        List<Double> scores = withWarmup(0.0, -0.05, -(ENTER + 0.01));

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        assertEquals(TrendRegime.DOWN, regimes.get(regimes.size() - 1));
    }

    @Test
    @DisplayName("Maintien sous la barrière : état UP conservé tant que le score reste >= EXIT (0), même sous ENTER (1/6)")
    void staysUpBelowBarrierAsLongAsAboveExit() {
        // Entrée en UP, puis score qui redescend sous ENTER sans jamais passer sous 0.
        List<Double> scores = withWarmup(ENTER + 0.1, ENTER - 0.05, 0.02, 0.001);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        int n = regimes.size();
        assertEquals(TrendRegime.UP, regimes.get(n - 4), "entrée en UP");
        assertEquals(TrendRegime.UP, regimes.get(n - 3), "reste UP sous la barrière ENTER");
        assertEquals(TrendRegime.UP, regimes.get(n - 2), "reste UP tant que score >= 0");
        assertEquals(TrendRegime.UP, regimes.get(n - 1), "reste UP tant que score >= 0");
    }

    @Test
    @DisplayName("Sortie à 0 : état UP -> RANGE dès que le score passe strictement sous 0")
    void exitsToRangeWhenScoreCrossesZero() {
        List<Double> scores = withWarmup(ENTER + 0.1, 0.01, -0.001);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        int n = regimes.size();
        assertEquals(TrendRegime.UP, regimes.get(n - 3));
        assertEquals(TrendRegime.UP, regimes.get(n - 2), "score encore >= 0");
        assertEquals(TrendRegime.RANGE, regimes.get(n - 1), "score < 0 -> sortie vers RANGE");
    }

    @Test
    @DisplayName("Symétrique : état DOWN -> RANGE dès que le score passe strictement au-dessus de 0")
    void exitsToRangeFromDownWhenScoreCrossesZero() {
        List<Double> scores = withWarmup(-(ENTER + 0.1), -0.01, 0.001);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        int n = regimes.size();
        assertEquals(TrendRegime.DOWN, regimes.get(n - 2));
        assertEquals(TrendRegime.RANGE, regimes.get(n - 1));
    }

    @Test
    @DisplayName("Bascule directe UP -> DOWN sur la même bougie si le score passe directement au-delà de -ENTER")
    void directFlipFromUpToDownOnSameCandle() {
        List<Double> scores = withWarmup(ENTER + 0.1, -(ENTER + 0.1));

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        int n = regimes.size();
        assertEquals(TrendRegime.UP, regimes.get(n - 2));
        assertEquals(TrendRegime.DOWN, regimes.get(n - 1),
                "score passant directement de > ENTER a < -ENTER doit basculer UP -> DOWN sur la meme bougie (via RANGE en interne)");
    }

    @Test
    @DisplayName("Bascule directe DOWN -> UP, symétrique")
    void directFlipFromDownToUpOnSameCandle() {
        List<Double> scores = withWarmup(-(ENTER + 0.1), ENTER + 0.1);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        int n = regimes.size();
        assertEquals(TrendRegime.DOWN, regimes.get(n - 2));
        assertEquals(TrendRegime.UP, regimes.get(n - 1));
    }

    @Test
    @DisplayName("Score exactement à ENTER (pas strictement supérieur) -> pas d'entrée, reste RANGE")
    void exactlyAtEnterThreshold_doesNotEnter() {
        List<Double> scores = withWarmup(ENTER);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        assertEquals(TrendRegime.RANGE, regimes.get(regimes.size() - 1));
    }

    @Test
    @DisplayName("Score exactement à 0 en état UP (pas strictement < EXIT) -> reste UP")
    void exactlyAtExitThreshold_staysUp() {
        List<Double> scores = withWarmup(ENTER + 0.1, 0.0);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        assertEquals(TrendRegime.UP, regimes.get(regimes.size() - 1));
    }

    @Test
    @DisplayName("Taille de la timeline retournée == taille de la liste de scores fournie")
    void timelineSizeMatchesScoresSize() {
        List<Double> scores = withWarmup(0.1, 0.2, 0.3, 0.4, 0.5);

        List<TrendRegime> regimes = RegressionHysteresisCalculator.computeTimeline(scores);

        assertEquals(scores.size(), regimes.size());
    }
}
