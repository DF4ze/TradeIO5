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
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

// Le MarketDatasetCache (singleton Spring) est indexé par flux natif
// (symbol + timeFrame + source + providerParam), volontairement SANS endTime/lookBack
// (cf. BucketKey). Les 3 tests ci-dessous réutilisent les mêmes symboles ("slowTF"/"fastTF")
// avec des TrendType différents selon le scénario : sans isolation du contexte Spring entre
// méthodes (et vis-à-vis des autres classes de test partageant le même contexte, ex:
// MarketDatasetEngineSpringTest qui utilise aussi "fastTF"/H1/UPTREND), elles partageraient
// le même Bucket et se pollueraient mutuellement.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Strategy - DoubleRsi")
@SpringBootTest
class DoubleRsiStrategyTest {
    private static final Logger logger = LoggerFactory.getLogger(DoubleRsiStrategyTest.class);

    @Autowired
    private StrategyRegistry strategyRegistry;
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;

    private static DomainClock clock;

    @BeforeAll
    static void init(){
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @BeforeEach
    void setUp() {
    }

    @Test
    void should_emit_SELL_when_rsi_is_overbuy() {

        AggregatedStrategySignal aggregatedSignal = compute(TrendType.UPTREND);


        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals( SignalType.BEARISH, aggregatedSignal.getFinalSignal() );
        assertTrue(aggregatedSignal.getScore() < 0);

    }

    @Test
    void should_emit_BUY_when_rsi_is_oversold() {

        AggregatedStrategySignal aggregatedSignal = compute(TrendType.DOWNTREND);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals( SignalType.BULLISH, aggregatedSignal.getFinalSignal() );
        assertTrue(aggregatedSignal.getScore() > 0);
    }

    @Test
    void should_HOLD_when_rsi_is_missing_or_invalid() {
        AggregatedStrategySignal aggregatedSignal = compute(TrendType.FLAT);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals( SignalType.NEUTRAL, aggregatedSignal.getFinalSignal() );
        assertEquals(0, aggregatedSignal.getScore());
    }

    private AggregatedStrategySignal compute(TrendType scenario){
        TimeFrame slowTF = TimeFrame.D1;
        TimeFrame fastTF = TimeFrame.H1;

        // Strategy Parameters
        StrategyParametersFactory.RsiParam slowParams =
                new StrategyParametersFactory.RsiParam( slowTF, 28.0, 69.0,31.0 );
        StrategyParametersFactory.RsiParam fastParams =
                new StrategyParametersFactory.RsiParam( fastTF, 14.0, 71.0,29.0 );
        StrategyParameters strategyParameters = StrategyParametersFactory.buildDoubleRsiStrategyParam(slowParams, fastParams);

        // Building Requests
        MarketDatasetRequest mdrSlow = new MarketDatasetRequest("slowTF", slowTF, 50, Instant.now(), MarketDataSource.MEMORY, scenario);
        MarketDatasetRequest mdrFast = new MarketDatasetRequest("fastTF", fastTF, 50, Instant.now(), MarketDataSource.MEMORY, scenario);

        // Building dataset
        MarketDataset slowDataset = marketDatasetEngine.getDataset(mdrSlow);
        MarketDataset fastDataset = marketDatasetEngine.getDataset(mdrFast);

        // Build context
        MarketContext context = new MarketContext(
                "BTCUSDT",
                new BigDecimal("42000"),
                clock,
                Map.of(
                        slowTF, slowDataset,
                        fastTF, fastDataset),
                new HashMap<>()
        );

        Strategy strategy = strategyRegistry.get( DoubleRsiStrategy.class.getSimpleName() );
        StrategySignal signal = strategy.evaluate(context, strategyParameters);

//        assertEquals( SignalType.SELL, weightedSignal.getType() );
//        assertTrue(weightedSignal.getScore() < 0);


        // Test of StrategyAggregator
        StrategyAggregatorParam sap = new StrategyAggregatorParam( strategy, strategyParameters );
        StrategyAggregatorParam sap2 = new StrategyAggregatorParam( strategy, strategyParameters );
        List<StrategyAggregatorParam> severalParams = new ArrayList<>(List.of(sap, sap2));
        AggregatedStrategySignal aggregatedSignal = StrategyAggregator.evaluate(context, severalParams);

        return aggregatedSignal;
    }
}