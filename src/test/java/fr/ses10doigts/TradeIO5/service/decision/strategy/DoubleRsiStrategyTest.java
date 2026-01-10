package fr.ses10doigts.TradeIO5.service.decision.strategy;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;
import fr.ses10doigts.TradeIO5.service.support.dataset.provider.InMemoryDatasetProvider;
import fr.ses10doigts.TradeIO5.service.support.dataset.provider.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.service.decision.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.decision.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.decision.strategy.impl.DoubleRsiStrategy;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl.RsiIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DoubleRsiStrategyTest {
    @Autowired
    private StrategyRegistry registry;

    @BeforeEach
    void setUp() {
    }

    @Test
    void should_emit_SELL_when_rsi_is_overbuy() {

        TimeFrame slowTF = TimeFrame.M1;
        TimeFrame fastTF = TimeFrame.D1;

        IndicatorParameters slowRsiParams = new IndicatorParameters(
                IndicatorType.RSI,
                Map.of( RsiIndicator.P_PERIOD_NAME, 12.0 ),                     // Numeric
                Map.of( DoubleRsiStrategy.P_TIME_FRAME_NAME, fastTF.toString()),    // String
                Map.of()                                                            // Boolean
        );
        IndicatorKey slowRsiKey = new IndicatorKey(IndicatorType.RSI, slowTF, slowRsiParams);

        IndicatorParameters fastRsiParams = new IndicatorParameters(
                IndicatorType.RSI,
                Map.of( RsiIndicator.P_PERIOD_NAME, 12.0 ),
                Map.of( DoubleRsiStrategy.P_TIME_FRAME_NAME, fastTF.toString() ),
                Map.of()
        );
        IndicatorKey fastRsiKey = new IndicatorKey(IndicatorType.RSI, fastTF, fastRsiParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(slowRsiKey, slowRsiParams);
        params.getIndicatorParameters().put(fastRsiKey, fastRsiParams);


        MarketDatasetProvider memoryProvider = new InMemoryDatasetProvider();
        MarketDataset slowDataset = memoryProvider.load(DatasetType.UPTREND, slowTF);
        MarketDataset fastDataset = memoryProvider.load(DatasetType.UPTREND, fastTF);

        MarketContext context = MarketContext.builder()
                .symbol("BTCUSDT")
                .series(Map.of(
                        slowTF, slowDataset.series(),
                        fastTF, fastDataset.series()))
                .lastPrice(new BigDecimal("42000"))
                .timestamp(Instant.now())
                .build();


        Strategy strategy = registry.get( "DoubleRsiStrategy" );
        StrategySignal signal = strategy.evaluate(context, params);

        assertEquals( SignalType.SELL, signal.getType() );
        assertTrue(signal.getConfidence() > 0);
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