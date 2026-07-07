package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.*;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Le MarketDatasetCache (singleton Spring) est indexé par flux natif (symbol + timeFrame +
// source + providerParam), sans endTime/lookBack. On utilise donc un symbole distinct par
// scénario/test pour éviter toute pollution croisée du même Bucket, y compris vis-à-vis des
// autres classes de test partageant le même contexte Spring.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Strategy - TrendConfirmation")
@SpringBootTest
class TrendConfirmationStrategyTest {
    private static final Logger logger = LoggerFactory.getLogger(TrendConfirmationStrategyTest.class);

    private static final TimeFrame TF = TimeFrame.H1;

    @Autowired
    private StrategyRegistry strategyRegistry;
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Test
    @DisplayName("Tendance haussière soutenue : EMA cross haussier confirmé par un ADX fort -> BULLISH, score positif")
    void should_emit_BULLISH_on_confirmed_uptrend() {
        // Seuil de surachat volontairement fixé à 100 (jamais dépassé) pour isoler ici le
        // comportement EMA+ADX du garde-fou RSI : celui-ci est couvert séparément par
        // should_reduce_confidence_when_rsi_guard_triggers_on_strong_uptrend, où le RSI vaut
        // toujours exactement 100 sur ce jeu de données synthétique en tendance pure et
        // continue (cf RsiIndicator : que des gains -> RSI = 100).
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TF, 10, 20, 14, 14,
                15.0, 25.0,
                100.0, 20.0
        );

        AggregatedStrategySignal aggregatedSignal = compute(TrendType.UPTREND, "trendConfirmUp", param);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals(SignalType.BULLISH, aggregatedSignal.getFinalSignal());
        assertTrue(aggregatedSignal.getScore() > 0);
    }

    @Test
    @DisplayName("Tendance baissière soutenue : EMA cross baissier confirmé par un ADX fort -> BEARISH, score négatif")
    void should_emit_BEARISH_on_confirmed_downtrend() {
        // Symétriquement, seuil de survente fixé à 0 (jamais franchi, le RSI vaut exactement 0
        // sur cette tendance baissière pure) pour isoler ici le comportement EMA+ADX.
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TF, 10, 20, 14, 14,
                15.0, 25.0,
                80.0, 0.0
        );

        AggregatedStrategySignal aggregatedSignal = compute(TrendType.DOWNTREND, "trendConfirmDown", param);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals(SignalType.BEARISH, aggregatedSignal.getFinalSignal());
        assertTrue(aggregatedSignal.getScore() < 0);
    }

    @Test
    @DisplayName("Marché plat/choppy : ADX bas neutralise le score (même sans biais EMA à filtrer ici)")
    void should_HOLD_on_flat_market_because_of_low_adx() {
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TF, 10, 20, 14, 14,
                15.0, 25.0,
                80.0, 20.0
        );

        AggregatedStrategySignal aggregatedSignal = compute(TrendType.FLAT, "trendConfirmFlat", param);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals(SignalType.NEUTRAL, aggregatedSignal.getFinalSignal());
        assertEquals(0, aggregatedSignal.getScore());
    }

    @Test
    @DisplayName("Garde-fou RSI : sur une tendance haussière très forte poussant le RSI en surachat extrême, le score EMA+ADX par ailleurs valide est neutralisé")
    void should_reduce_confidence_when_rsi_guard_triggers_on_strong_uptrend() {
        // Même scénario UPTREND (EMA cross haussier confirmé par un ADX fort) que le premier
        // test, mais avec un seuil de surachat réaliste (70) : le RSI vaut exactement 100 sur ce
        // jeu de données synthétique en tendance monotone parfaite (cf RsiIndicator), il dépasse
        // donc largement le seuil et le garde-fou neutralise complètement le score.
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TF, 10, 20, 14, 14,
                15.0, 25.0,
                70.0, 20.0
        );

        AggregatedStrategySignal aggregatedSignal = compute(TrendType.UPTREND, "trendConfirmGuard", param);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals(SignalType.NEUTRAL, aggregatedSignal.getFinalSignal());
        assertEquals(0, aggregatedSignal.getScore());
    }

    private AggregatedStrategySignal compute(TrendType scenario, String symbol, StrategyParametersFactory.TrendConfirmationParam param) {

        StrategyParameters strategyParameters = StrategyParametersFactory.buildTrendConfirmationStrategyParam(param);

        // 60 bougies : suffisant pour l'EMA lente (20), l'ADX (2 x 14 = 28) et le RSI (14 + 1)
        MarketDatasetRequest mdr = new MarketDatasetRequest(symbol, TF, 60, Instant.now(), MarketDataSource.MEMORY, scenario);
        MarketDataset dataset = marketDatasetEngine.getDataset(mdr);

        MarketContext context = new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(TF, dataset),
                new HashMap<>()
        );

        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategySignal signal = strategy.evaluate(context, strategyParameters);

        assertTrue(signal.isValid(), "Strategy signal should be valid (no indicator error) : " + signal.getReason());

        StrategyAggregatorParam sap = new StrategyAggregatorParam(strategy, strategyParameters);
        StrategyAggregatorParam sap2 = new StrategyAggregatorParam(strategy, strategyParameters);
        List<StrategyAggregatorParam> severalParams = new ArrayList<>(List.of(sap, sap2));

        return StrategyAggregator.evaluate(context, severalParams);
    }
}
