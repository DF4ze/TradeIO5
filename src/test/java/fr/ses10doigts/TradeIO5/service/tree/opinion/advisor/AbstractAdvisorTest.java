package fr.ses10doigts.tradeIO5.service.tree.opinion.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@DisplayName("Indicator External- Abstract")
class AbstractAdvisorTest {

    private static DomainClock clock;

    @BeforeAll
    static void init(){
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Test
    void buildPrompt() {
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

        OpinionContext dc = new OpinionContext(
                null,
                null,
                mc,
                Map.of(),
                clock
        );

        OpenAIAdvisor adv = new OpenAIAdvisor(null);
        String prompt = adv.buildPrompt(dc);

        System.out.println(prompt);
    }
}