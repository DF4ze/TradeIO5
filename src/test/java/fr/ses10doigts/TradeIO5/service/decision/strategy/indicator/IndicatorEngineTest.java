package fr.ses10doigts.TradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;
import fr.ses10doigts.TradeIO5.service.support.dataset.provider.InMemoryDatasetProvider;
import fr.ses10doigts.TradeIO5.service.support.dataset.provider.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl.MacdIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class IndicatorEngineTest {
    @Autowired
    private IndicatorEngine indicatorEngine;

    @BeforeEach
    void setUp() {
    }

    @Test
    void execute_macd_with_dependencies() {
        IndicatorParameters macdParams = new IndicatorParameters(
                IndicatorType.MACD,
                Map.of(
                        MacdIndicator.P_FAST_PERIOD_NAME, 12.0,
                        MacdIndicator.P_SLOW_PERIOD_NAME, 26.0
                ),
                Map.of(),
                Map.of()
        );
        MarketDatasetProvider memoryProvider = new InMemoryDatasetProvider();
        MarketDataset dataset = memoryProvider.load(DatasetType.UPTREND, TimeFrame.H1);

        IndicatorContext context = IndicatorContext.builder()
                .marketData(dataset.series())
                .dependencies(Map.of())
                .build();

        IndicatorSnapshot snapshot =
                indicatorEngine.execute(
                        context,
                        macdParams
                );

        assertEquals(IndicatorType.MACD, snapshot.getIndicatorCode());
        assertTrue(snapshot.getValue().isValid());
    }
}