package fr.ses10doigts.TradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;
import fr.ses10doigts.TradeIO5.service.support.dataset.provider.InMemoryDatasetProvider;
import fr.ses10doigts.TradeIO5.service.support.dataset.provider.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.IndicatorEngine;
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
                IndicatorCode.MACD,
                Map.of(
                        "fastPeriod", 12.0,
                        "slowPeriod", 26.0
                ),
                Map.of(),
                Map.of()
        );
        MarketDatasetProvider memoryProvider = new InMemoryDatasetProvider();
        MarketDataset dataset = memoryProvider.load(DatasetType.UPTREND);

        IndicatorContext context = IndicatorContext.builder()
                .marketData(dataset.series())
                .dependencies(Map.of())
                .build();

        IndicatorSnapshot snapshot =
                indicatorEngine.execute(
                        IndicatorCode.MACD,
                        context,
                        macdParams
                );

        assertEquals(IndicatorCode.MACD, snapshot.getIndicatorCode());
        assertTrue(snapshot.getValue().isValid());
    }
}