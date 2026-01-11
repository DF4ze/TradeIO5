package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorValue;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.marketdataset.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.service.marketdataset.provider.InMemoryDatasetProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmaIndicatorTest {



    @BeforeEach
    void setUp() {
    }

    @Test
    void compute() {
        Indicator indicator = new EmaIndicator();

        IndicatorParameters params = new IndicatorParameters(
                IndicatorType.EMA,
                Map.of(EmaIndicator.P_PERIOD_NAME, 3.0),
                Map.of(),
                Map.of()
        );

        MarketDatasetProvider memoryProvider = new InMemoryDatasetProvider(MarketScenario.UPTREND);
        MarketDataRequest mdr = new MarketDataRequest("test_ema", TimeFrame.H1, 5, null);
        MarketDataSeries dataset = memoryProvider.load( mdr );

        IndicatorContext context = IndicatorContext.builder()
                .marketData(dataset)
                .dependencies(Map.of())
                .build();

        IndicatorValue value = indicator.compute(context, params);

        assertTrue(value.isValid());
        assertNotNull(value.getValue());
    }
}