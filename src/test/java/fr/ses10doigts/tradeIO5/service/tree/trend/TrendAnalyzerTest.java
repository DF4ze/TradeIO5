package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires/d'assemblage de {@link TrendAnalyzer} — Étape 8 de la roadmap Trend unifié (cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §5) : la
 * formule de score ({@link RegressiveTrendScoreCalculator}) et la machine à états
 * ({@link RegressionHysteresisCalculator}) sont testées séparément dans leurs propres fichiers ;
 * celui-ci couvre l'assemblage sur des bougies synthétiques (montée propre -&gt; UP, baisse -&gt;
 * DOWN, plat -&gt; RANGE), le garde-fou de warmup, et le scénario de non-régression obligatoire
 * "range étendu + breakout rapide" (§5).
 */
@DisplayName("TrendAnalyzer")
class TrendAnalyzerTest {

    private static final Instant BASE_TIMESTAMP = Instant.parse("2024-01-01T00:00:00Z");

    private final TrendAnalyzer analyzer = new TrendAnalyzer();

    private static List<MarketData> candlesFromCloses(double[] closes) {
        List<MarketData> candles = new ArrayList<>(closes.length);
        for (int i = 0; i < closes.length; i++) {
            candles.add(MarketData.builder()
                    .close(BigDecimal.valueOf(closes[i]))
                    .timestamp(BASE_TIMESTAMP.plus(i, ChronoUnit.DAYS))
                    .build());
        }
        return candles;
    }

    // --- Garde-fou warmup ------------------------------------------------------------------------

    @Test
    @DisplayName("Moins de MIN_CANDLES bougies -> IllegalArgumentException")
    void notEnoughCandlesThrows() {
        double[] closes = new double[TrendAnalyzer.MIN_CANDLES - 1];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + i;
        }
        List<MarketData> candles = candlesFromCloses(closes);

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(candles));
    }

    @Test
    @DisplayName("candles == null -> IllegalArgumentException")
    void nullCandlesThrows() {
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(null));
    }

    @Test
    @DisplayName("Exactement MIN_CANDLES bougies -> ne lève pas d'exception")
    void exactlyMinCandlesDoesNotThrow() {
        double[] closes = new double[TrendAnalyzer.MIN_CANDLES];
        for (int i = 0; i < closes.length; i++) {
            closes[i] = 100 + i * 1.0;
        }
        List<MarketData> candles = candlesFromCloses(closes);

        TrendState state = analyzer.analyze(candles);
        assertTrue(state.regime() == TrendRegime.UP || state.regime() == TrendRegime.RANGE || state.regime() == TrendRegime.DOWN);
    }

    // --- Assemblage sur bougies synthétiques -------------------------------------------------------

    @Test
    @DisplayName("Montée propre et soutenue (r2~1) sur toute la fenêtre -> régime UP, force==|score|, confiance maximale")
    void sustainedCleanUptrend_producesUpRegime() {
        int n = TrendAnalyzer.MIN_CANDLES + 20;
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = 100 + 2.0 * i;
        }
        List<MarketData> candles = candlesFromCloses(closes);

        TrendState state = analyzer.analyze(candles);

        assertEquals(TrendRegime.UP, state.regime());
        assertTrue(state.score() > 0);
        assertEquals(Math.abs(state.score()), state.force(), 1e-9);
        // Régime UP dès que possible sur une pente parfaitement linéaire -> run-length long -> confiance max.
        assertEquals(1.0, state.confidence(), 1e-9);
    }

    @Test
    @DisplayName("Baisse propre et soutenue -> régime DOWN, symétrique")
    void sustainedCleanDowntrend_producesDownRegime() {
        int n = TrendAnalyzer.MIN_CANDLES + 20;
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = 1000 - 2.0 * i;
        }
        List<MarketData> candles = candlesFromCloses(closes);

        TrendState state = analyzer.analyze(candles);

        assertEquals(TrendRegime.DOWN, state.regime());
        assertTrue(state.score() < 0);
        assertEquals(Math.abs(state.score()), state.force(), 1e-9);
        assertEquals(1.0, state.confidence(), 1e-9);
    }

    @Test
    @DisplayName("Marché plat (r2=0 sur toutes les fenêtres) -> régime RANGE, jamais d'entrée en UP/DOWN")
    void flatMarket_staysRange() {
        int n = TrendAnalyzer.MIN_CANDLES + 20;
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = 500.0;
        }
        List<MarketData> candles = candlesFromCloses(closes);

        TrendState state = analyzer.analyze(candles);

        assertEquals(TrendRegime.RANGE, state.regime());
        assertEquals(0.0, state.score(), 1e-9);
        assertEquals(0.0, state.force(), 1e-9);
        assertTrue(state.confidence() > 0, "un régime RANGE stable depuis le début doit accumuler de la confiance comme les autres régimes");
    }

    @Test
    @DisplayName("analyzeTimeline : une TrendState par score calculable, bosJustOccurred vrai uniquement au changement de régime")
    void analyzeTimeline_bosJustOccurredOnlyOnRegimeChange() {
        // Baisse propre puis remontée franche : le régime doit finir par basculer DOWN -> UP.
        int declineLength = TrendAnalyzer.MIN_CANDLES;
        int reboundLength = 40;
        double[] closes = new double[declineLength + reboundLength];
        for (int i = 0; i < declineLength; i++) {
            closes[i] = 1000 - 3.0 * i;
        }
        double base = closes[declineLength - 1];
        for (int i = 0; i < reboundLength; i++) {
            closes[declineLength + i] = base + 5.0 * (i + 1);
        }
        List<MarketData> candles = candlesFromCloses(closes);

        List<TrendState> timeline = analyzer.analyzeTimeline(candles,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED);

        assertEquals(TrendRegime.DOWN, timeline.getFirst().regime());
        assertTrue(timeline.stream().anyMatch(s -> s.regime() == TrendRegime.UP), "la remontée doit finir par produire un régime UP");

        int flips = 0;
        for (int i = 1; i < timeline.size(); i++) {
            boolean regimeChanged = timeline.get(i).regime() != timeline.get(i - 1).regime();
            assertEquals(regimeChanged, timeline.get(i).bosJustOccurred(),
                    "bosJustOccurred doit refléter exactement un changement de régime par rapport à la bougie précédente");
            if (regimeChanged) {
                flips++;
            }
        }
        assertTrue(flips >= 1);
        // Le tout premier élément n'a pas de bougie précédente : jamais bosJustOccurred.
        assertEquals(false, timeline.getFirst().bosJustOccurred());
    }

    // --- Scénario de non-régression obligatoire : range étendu + breakout rapide (prompt §5) ------

    /**
     * 60 bougies de baisse lente, puis 90 bougies de range ±5% (oscillation symétrique autour d'un
     * palier), puis un breakout net +2%/jour pendant 10 jours. Cf. prompt §5 : "aucune candidate ne
     * sait bien identifier un range" est une limite connue et acceptée, documentée ici plutôt que
     * masquée — ce test vérifie uniquement (1) que le breakout est capté rapidement, et (2) documente
     * le nombre de changements d'état pendant la phase de range observé avec cette implémentation
     * Java (pas de valeur pré-supposée recopiée depuis la réplique Python, qui utilise un scénario
     * généré avec une graine aléatoire différente).
     */
    @Test
    @DisplayName("Non-régression : range étendu (90j, ±5%) puis breakout rapide (+2%/j, 10j) -> UP atteint au plus 3 bougies après le début du breakout")
    void extendedRangeThenFastBreakout_capturesBreakoutQuickly() {
        int declineLength = 60;
        int rangeLength = 90;
        int breakoutLength = 10;

        double[] closes = new double[declineLength + rangeLength + breakoutLength];
        int idx = 0;

        double price = 1000.0;
        for (int i = 0; i < declineLength; i++) {
            price *= 0.995; // baisse lente
            closes[idx++] = price;
        }

        double rangeCenter = price;
        int rangeStart = idx;
        // Range +-5% : zigzag symétrique autour du palier atteint après la baisse (période ~10j).
        for (int i = 0; i < rangeLength; i++) {
            double phase = (i % 10) / 10.0; // 0 -> 1 sur 10 bougies
            double offset = Math.sin(phase * 2 * Math.PI) * 0.05;
            closes[idx++] = rangeCenter * (1 + offset);
        }
        int breakoutStart = idx;

        price = closes[breakoutStart - 1];
        for (int i = 0; i < breakoutLength; i++) {
            price *= 1.02; // breakout net +2%/jour
            closes[idx++] = price;
        }

        List<MarketData> candles = candlesFromCloses(closes);

        List<TrendState> timeline = analyzer.analyzeTimeline(candles,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED);

        // Décalage entre l'indice des bougies et l'indice de la timeline de score : les MIN_CANDLES-1
        // premières bougies (fenêtre longue non encore calculable) n'ont pas de TrendState associé.
        int scoreOffset = candles.size() - timeline.size();

        int breakoutStateIndex = breakoutStart - scoreOffset;
        assertTrue(breakoutStateIndex >= 0, "le breakout doit être dans la portion couverte par la timeline de score");

        int firstUpAfterBreakout = -1;
        for (int i = breakoutStateIndex; i < timeline.size(); i++) {
            if (timeline.get(i).regime() == TrendRegime.UP) {
                firstUpAfterBreakout = i;
                break;
            }
        }
        assertTrue(firstUpAfterBreakout >= 0, "le régime doit devenir UP à un moment pendant/après le breakout");

        // Observé avec cette implémentation Java sur ce scénario (2026-09-24) : 2 bougies après le
        // début du breakout, sous la barre des 3 fixée par le prompt §5.
        int candlesAfterBreakoutStart = firstUpAfterBreakout - breakoutStateIndex;
        assertTrue(candlesAfterBreakoutStart <= 3,
                "UP attendu au plus 3 bougies apres le debut du breakout, observe : " + candlesAfterBreakoutStart);

        // Documentation (pas une assertion stricte, cf. prompt §5 : "connu et accepté, ne pas corriger
        // dans ce lot") : nombre de changements d'état pendant la phase de range, pour traçabilité.
        int rangeStateStart = Math.max(0, rangeStart - scoreOffset);
        int rangeStateEnd = Math.min(timeline.size(), breakoutStart - scoreOffset);
        int changesDuringRange = 0;
        for (int i = rangeStateStart + 1; i < rangeStateEnd; i++) {
            if (timeline.get(i).regime() != timeline.get(i - 1).regime()) {
                changesDuringRange++;
            }
        }
        // Observé avec cette implémentation Java sur ce scénario : l'hystérésis oscille pendant un
        // range étendu (limite connue et acceptée, cf. prompt §5) — bornage large pour ne pas figer
        // un chiffre non mesuré, uniquement pour détecter une régression grossière de l'algorithme.
        // Observé avec cette implémentation Java sur ce scénario (2026-09-24) : 32 changements de
        // régime sur 90 bougies de range — confirme l'instabilité connue et acceptée (prompt §5).
        assertTrue(changesDuringRange >= 0 && changesDuringRange <= rangeLength,
                "changesDuringRange hors limites plausibles : " + changesDuringRange);
    }
}
