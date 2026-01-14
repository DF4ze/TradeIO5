package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.*;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DoubleRsiStrategyTest {
    private static final Logger logger = LoggerFactory.getLogger(DoubleRsiStrategyTest.class);

    @Autowired
    private StrategyRegistry strategyRegistry;
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;

    @BeforeEach
    void setUp() {
    }

    @Test
    void should_emit_SELL_when_rsi_is_overbuy() {

        AggregatedStrategySignal aggregatedSignal = compute(MarketScenario.UPTREND);


        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals( SignalType.SELL, aggregatedSignal.getFinalSignal() );
        assertTrue(aggregatedSignal.getScore() < 0);

    }

    @Test
    void should_emit_BUY_when_rsi_is_oversold() {

        AggregatedStrategySignal aggregatedSignal = compute(MarketScenario.DOWNTREND);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals( SignalType.BUY, aggregatedSignal.getFinalSignal() );
        assertTrue(aggregatedSignal.getScore() > 0);
    }

    @Test
    void should_HOLD_when_rsi_is_missing_or_invalid() {
        AggregatedStrategySignal aggregatedSignal = compute(MarketScenario.FLAT);

        logger.debug("Explanation : {}", aggregatedSignal.getExplanation());

        assertEquals( SignalType.HOLD, aggregatedSignal.getFinalSignal() );
        assertEquals(0, aggregatedSignal.getScore());
    }

    private AggregatedStrategySignal compute(MarketScenario scenario){
        TimeFrame slowTF = TimeFrame.M1;
        TimeFrame fastTF = TimeFrame.D1;

        // Strategy Parameters
        StrategyParametersFactory.RsiParam slowParams =
                new StrategyParametersFactory.RsiParam( slowTF, 28.0, 69.0,31.0 );
        StrategyParametersFactory.RsiParam fastParams =
                new StrategyParametersFactory.RsiParam( fastTF, 14.0, 71.0,29.0 );
        StrategyParameters strategyParameters = StrategyParametersFactory.buildDoubleRsiStrategyParam(slowParams, fastParams);

        // Building Requests
        MarketDatasetRequest mdrSlow = new MarketDatasetRequest("slowTF", slowTF, 50, null, MarketDataSource.MEMORY, MarketScenario.UPTREND);
        MarketDatasetRequest mdrFast = new MarketDatasetRequest("fastTF", fastTF, 50, null, MarketDataSource.MEMORY, MarketScenario.UPTREND);

        // Building dataset
        MarketDataset slowDataset = marketDatasetEngine.refresh(mdrSlow);
        MarketDataset fastDataset = marketDatasetEngine.refresh(mdrFast);

        // Build context
        MarketContext context = MarketContext.builder()
                .symbol("BTCUSDT")
                .series(Map.of(
                        slowTF, slowDataset,
                        fastTF, fastDataset))
                .lastPrice(new BigDecimal("42000"))
                .timestamp(Instant.now())
                .build();

        Strategy strategy = strategyRegistry.get( DoubleRsiStrategy.class.getSimpleName() );
        StrategySignal signal = strategy.evaluate(context, strategyParameters);

//        assertEquals( SignalType.SELL, signal.getType() );
//        assertTrue(signal.getScore() < 0);


        // Test of StrategyAggregator
        StrategyAggregatorParam sap = new StrategyAggregatorParam( strategy, strategyParameters );
        StrategyAggregatorParam sap2 = new StrategyAggregatorParam( strategy, strategyParameters );
        List<StrategyAggregatorParam> severalParams = new ArrayList<>(List.of(sap, sap2));
        AggregatedStrategySignal aggregatedSignal = StrategyAggregator.evaluate(context, severalParams);

        return aggregatedSignal;
    }
}