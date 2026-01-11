package fr.ses10doigts.tradeIO5.service.decision.strategy;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.*;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.decision.DecisionRegistry;
import fr.ses10doigts.tradeIO5.service.decision.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.decision.strategy.impl.DoubleRsiStrategy;
import fr.ses10doigts.tradeIO5.service.marketdataset.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.service.marketdataset.provider.InMemoryDatasetProvider;
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
    private StrategyRegistry registry;

    @BeforeEach
    void setUp() {
    }

    @Test
    void should_emit_SELL_when_rsi_is_overbuy() {

        TimeFrame slowTF = TimeFrame.M1;
        TimeFrame fastTF = TimeFrame.D1;

        // Strategy Parameters
        StrategyParametersFactory.RsiParam slowParams =
                new StrategyParametersFactory.RsiParam( slowTF, 28.0, 69.0,31.0 );
        StrategyParametersFactory.RsiParam fastParams =
                new StrategyParametersFactory.RsiParam( fastTF, 14.0, 71.0,29.0 );
        StrategyParameters strategyParameters = StrategyParametersFactory.buildDoubleRsiStrategyParam(slowParams, fastParams);

        // Building dataset & MarketContext
        MarketDatasetProvider memoryProvider = new InMemoryDatasetProvider(MarketScenario.UPTREND);
        MarketDataRequest mdrSlow = new MarketDataRequest("slowTF", slowTF, 50, null);
        MarketDataRequest mdrFast = new MarketDataRequest("fastTF", fastTF, 50, null);
        MarketDataSeries slowDataset = memoryProvider.load(mdrSlow);
        MarketDataSeries fastDataset = memoryProvider.load(mdrFast);

        MarketContext context = MarketContext.builder()
                .symbol("BTCUSDT")
                .series(Map.of(
                        slowTF, slowDataset,
                        fastTF, fastDataset))
                .lastPrice(new BigDecimal("42000"))
                .timestamp(Instant.now())
                .build();

        Strategy strategy = registry.get( DoubleRsiStrategy.class.getSimpleName() );
        StrategySignal signal = strategy.evaluate(context, strategyParameters);

        assertEquals( SignalType.SELL, signal.getType() );
        assertTrue(signal.getScore() > 0);


        // Test of StrategyAggregator
        StrategyAggregatorParam sap = new StrategyAggregatorParam( strategy, strategyParameters );
        StrategyAggregatorParam sap2 = new StrategyAggregatorParam( strategy, strategyParameters );
        List<StrategyAggregatorParam> severalParams = new ArrayList<>(List.of(sap, sap2));
        AggregatedStrategySignal aggregatedSignal = StrategyAggregator.evaluate(context, severalParams);

        assertEquals( SignalType.SELL, aggregatedSignal.getFinalSignal() );
        assertTrue(aggregatedSignal.getScore() > 0);

    }

    @Test
    void should_emit_SELL_when_rsi_is_overbought() {
/*        MarketContext context = MarketContext.builder()
                .indicators(Map.of(
                        IndicatorType.RSI,
                        IndicatorValue.builder()
                                .value(75d)
                                .valid(true)
                                .build()
                ))
                .build();

        Strategy strategy = registry.get("RsiStrategy");
        StrategySignal signal = strategy.evaluate(context);

        assertEquals(SignalType.SELL, signal.getType());
*/    }

    @Test
    void should_HOLD_when_rsi_is_missing_or_invalid() {
/*        MarketContext context = MarketContext.builder()
                .indicators(Map.of(
                        IndicatorType.RSI,
                        IndicatorValue.invalid()
                ))
                .build();

        Strategy strategy = registry.get("RsiStrategy");
        StrategySignal signal = strategy.evaluate(context);

        assertEquals(SignalType.HOLD, signal.getType());
*/    }
}