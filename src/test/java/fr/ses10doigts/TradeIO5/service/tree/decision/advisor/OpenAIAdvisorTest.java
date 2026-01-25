package fr.ses10doigts.tradeIO5.service.tree.decision.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.MacdIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.RsiIndicator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - OpenAI")
@SpringBootTest
class OpenAIAdvisorTest {

    @Autowired
    OpenAIAdvisor advisor;

    private static DomainClock clock;

    @BeforeAll
    static void init(){
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Disabled("Test temporairement désactivé")
    @Test
    void callModelTest() {
        IndicatorParameters rsiP = IndicatorParameters.builder()
                .indicatorType(IndicatorType.RSI)
                .numerics(Map.of(RsiIndicator.P_PERIOD_NAME, 14.0))
                .build();
        IndicatorParameters macdP = IndicatorParameters.builder()
                .indicatorType(IndicatorType.MACD)
                .numerics(Map.of(
                        MacdIndicator.P_FAST_PERIOD_NAME, 12.0,
                        MacdIndicator.P_SLOW_PERIOD_NAME, 26.0)
                )
                .build();

        IndicatorResult rsiR = IndicatorResult.builder()
                .value(35.0)
                .valid(true)
                .build();
        IndicatorResult macdR = IndicatorResult.builder()
                .value(35.0)
                .valid(true)
                .build();

        IndicatorKey rsiKey = new IndicatorKey(IndicatorType.RSI, TimeFrame.H1, rsiP);
        IndicatorKey macdKey = new IndicatorKey(IndicatorType.MACD, TimeFrame.H1, macdP);

        MarketContext mc = new MarketContext(
                "BTCUSDT",
                BigDecimal.valueOf(98500),
                clock,
                Map.of(),
                Map.of(
                        rsiKey, rsiR,
                        macdKey, macdR
                )
        );

        DecisionContext dc = new DecisionContext(
                null,
                null,
                mc,
                Map.of(),
                clock
        );

        LlmAdvice advise = advisor.advise(dc);

        System.out.println("Advice : "+advise);

        assertNotNull(advise);
        assertTrue(advise.isValid());
        assertNotNull(advise.getAction());
        assertTrue(advise.getConfidence() > 0.0);
        assertNotNull(advise.getRationale());
    }
}