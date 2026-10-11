package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendAnalyzer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Réécrite le 2026-09-24 pour l'Étape 8 de la roadmap Trend unifié (cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md}) :
 * contrat d'entrée passé de SWING_STRUCTURE+ADX à 3x LINEAR_REGRESSION (7/14/30, D1), score/régime
 * calculés par {@link TrendAnalyzer} (régression multi-fenêtres à hystérésis) plutôt que par
 * SWING_STRUCTURE+ADX.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Strategy - TrendConfirmation")
@SpringBootTest
class TrendConfirmationStrategyTest {
    private static final Logger logger = LoggerFactory.getLogger(TrendConfirmationStrategyTest.class);

    private static final TimeFrame TF = TimeFrame.D1;

    @Autowired
    private StrategyRegistry strategyRegistry;

    private static DomainClock clock;
    private static Instant baseTimestamp;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
        baseTimestamp = Instant.parse("2024-01-01T00:00:00Z");
    }

    private static List<MarketData> candles(double[] closes) {
        List<MarketData> data = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            data.add(MarketData.builder()
                    .close(BigDecimal.valueOf(closes[i]))
                    .timestamp(baseTimestamp.plus(i, ChronoUnit.DAYS))
                    .build());
        }
        return data;
    }

    private static double[] linear(int n, double start, double step) {
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = start + step * i;
        }
        return closes;
    }

    private static MarketContext context(double[] closes) {
        MarketDataset dataset = MarketDataset.builder()
                .marketDatas(candles(closes))
                .timeFrame(TF)
                .build();
        return new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(TF, dataset),
                new java.util.HashMap<>()
        );
    }

    private StrategySignal evaluate(double[] closes) {
        StrategyParameters strategyParameters =
                StrategyParametersFactory.buildTrendConfirmationStrategyParam(
                        StrategyParametersFactory.TrendConfirmationParam.defaults(TF));

        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategySignal signal = strategy.evaluate(context(closes), strategyParameters);
        logger.debug("signal={}", signal);
        return signal;
    }

    @Test
    @DisplayName("Montée propre et soutenue -> BULLISH, score >= barrière")
    void should_be_bullish_on_sustained_uptrend() {
        double[] closes = linear(TrendAnalyzer.MIN_CANDLES + 20, 100, 2.0);
        StrategySignal signal = evaluate(closes);

        assertTrue(signal.isValid(), "signal should be valid : " + signal.getReason());
        assertEquals(SignalType.BULLISH, signal.getType());
        assertTrue(signal.getScore() >= MarketOpinionHelper.BARRIER, "expected score >= barrier, got " + signal.getScore());
    }

    @Test
    @DisplayName("Baisse propre et soutenue -> BEARISH, score symétrique")
    void should_be_bearish_on_sustained_downtrend() {
        double[] closes = linear(TrendAnalyzer.MIN_CANDLES + 20, 1000, -2.0);
        StrategySignal signal = evaluate(closes);

        assertTrue(signal.isValid(), "signal should be valid : " + signal.getReason());
        assertEquals(SignalType.BEARISH, signal.getType());
        assertTrue(signal.getScore() <= -MarketOpinionHelper.BARRIER, "expected score <= -barrier, got " + signal.getScore());
    }

    @Test
    @DisplayName("Marché plat -> NEUTRAL, score nul (régime RANGE, jamais entré en UP/DOWN)")
    void should_be_neutral_on_flat_market() {
        double[] closes = linear(TrendAnalyzer.MIN_CANDLES + 20, 500, 0.0);
        StrategySignal signal = evaluate(closes);

        assertTrue(signal.isValid(), "signal should be valid : " + signal.getReason());
        assertEquals(SignalType.NEUTRAL, signal.getType());
        assertEquals(0.0, signal.getScore());
    }

    @Test
    @DisplayName("Historique insuffisant (< MIN_CANDLES) -> signal invalide")
    void should_be_invalid_on_insufficient_history() {
        double[] closes = linear(TrendAnalyzer.MIN_CANDLES - 1, 100, 1.0);
        StrategySignal signal = evaluate(closes);

        assertFalse(signal.isValid());
    }

    @Test
    @DisplayName("accepts(...) : accepte exactement 3xLINEAR_REGRESSION, refuse toute autre combinaison (dont l'ancien contrat SWING_STRUCTURE+ADX)")
    void accepts_onlyExactlyThreeLinearRegression() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());

        assertTrue(strategy.accepts(StrategyParametersFactory.buildTrendConfirmationStrategyParam(
                StrategyParametersFactory.TrendConfirmationParam.defaults(TF))));

        // Ancien contrat (1xSWING_STRUCTURE + 1xADX) : refusé (mauvaise taille ET mauvais types).
        StrategyParameters oldContract = new StrategyParameters();
        fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters swingParams =
                fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters.builder()
                        .indicatorType(fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.SWING_STRUCTURE)
                        .numerics(Map.of()).strings(Map.of()).booleans(Map.of()).build();
        fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters adxParams =
                fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters.builder()
                        .indicatorType(fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.ADX)
                        .numerics(Map.of("period", 14.0)).strings(Map.of()).booleans(Map.of()).build();
        oldContract.getIndicatorParameters().put(
                new fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey(
                        fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.SWING_STRUCTURE, TF, swingParams),
                swingParams);
        oldContract.getIndicatorParameters().put(
                new fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey(
                        fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.ADX, TF, adxParams),
                adxParams);
        assertFalse(strategy.accepts(oldContract), "l'ancien contrat SWING_STRUCTURE+ADX doit etre refuse");

        // 2xLINEAR_REGRESSION + 1xEMA (bonne taille, mauvais type) : refusé.
        StrategyParameters wrongTypes = new StrategyParameters();
        fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters lr1 =
                fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters.builder()
                        .indicatorType(fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.LINEAR_REGRESSION)
                        .numerics(Map.of("period", 7.0)).strings(Map.of()).booleans(Map.of()).build();
        fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters lr2 =
                fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters.builder()
                        .indicatorType(fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.LINEAR_REGRESSION)
                        .numerics(Map.of("period", 14.0)).strings(Map.of()).booleans(Map.of()).build();
        fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters ema =
                fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters.builder()
                        .indicatorType(fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.EMA)
                        .numerics(Map.of("period", 30.0)).strings(Map.of()).booleans(Map.of()).build();
        wrongTypes.getIndicatorParameters().put(new fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey(
                fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.LINEAR_REGRESSION, TF, lr1), lr1);
        wrongTypes.getIndicatorParameters().put(new fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey(
                fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.LINEAR_REGRESSION, TF, lr2), lr2);
        wrongTypes.getIndicatorParameters().put(new fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey(
                fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType.EMA, TF, ema), ema);
        assertFalse(strategy.accepts(wrongTypes), "2xLINEAR_REGRESSION + 1xEMA doit etre refuse");

        // StrategyParameters vide : refusé.
        assertFalse(strategy.accepts(new StrategyParameters()));
    }

    @Test
    @DisplayName("getRequiredCandles(...) : au moins TrendAnalyzer.MIN_CANDLES sur le TimeFrame des indicateurs")
    void getRequiredCandles_returnsAtLeastMinCandles() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParameters strategyParameters = StrategyParametersFactory.buildTrendConfirmationStrategyParam(
                StrategyParametersFactory.TrendConfirmationParam.defaults(TF));

        Map<TimeFrame, Integer> required = strategy.getRequiredCandles(strategyParameters);

        assertEquals(TrendAnalyzer.MIN_CANDLES, required.get(TF));
    }
}
