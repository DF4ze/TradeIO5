package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.MacdIndicator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator - *Engine")
@SpringBootTest
class IndicatorEngineTest {
    @Autowired
    private IndicatorEngine indicatorEngine;
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
    void execute_macd_with_dependencies() {
        IndicatorParameters macdParams = new IndicatorParameters(
                IndicatorType.MACD,
                Map.of(
                        MacdIndicator.P_FAST_PERIOD_NAME, 12.0,
                        MacdIndicator.P_SLOW_PERIOD_NAME, 26.0
                ),
                Map.of(),
                Map.of(),
                null
        );

        MarketDatasetRequest mdr = new MarketDatasetRequest("macd_updtrend", TimeFrame.H1, 50, Instant.now(), MarketDataSource.MEMORY, TrendType.UPTREND);

        MarketDataset dataset = marketDatasetEngine.getDataset(mdr);

        IndicatorContext context = new IndicatorContext(
                "BTCUSDT",
                mdr.timeFrame(),
                dataset,
                Map.of(),
                clock
        );

        IndicatorSnapshot snapshot =
                indicatorEngine.execute(
                        context,
                        macdParams
                );

        assertEquals(IndicatorType.MACD, snapshot.getIndicatorType());
        assertTrue(snapshot.getResult().isValid());
    }
}