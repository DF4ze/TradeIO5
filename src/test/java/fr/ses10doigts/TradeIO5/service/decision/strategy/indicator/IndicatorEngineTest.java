package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl.MacdIndicator;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
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
    @Autowired
    private MarketDatasetEngine marketDatasetEngine;

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

        MarketDatasetRequest mdr = new MarketDatasetRequest("macd_updtrend", TimeFrame.H1, 50, null, MarketDataSource.MEMORY, MarketScenario.UPTREND);

        MarketDataset dataset = marketDatasetEngine.refresh(mdr);

        IndicatorContext context = IndicatorContext.builder()
                .marketDataset(dataset)
                .dependencies(Map.of())
                .build();

        IndicatorSnapshot snapshot =
                indicatorEngine.execute(
                        context,
                        macdParams
                );

        assertEquals(IndicatorType.MACD, snapshot.getIndicatorCode());
        assertTrue(snapshot.getResult().isValid());
    }
}