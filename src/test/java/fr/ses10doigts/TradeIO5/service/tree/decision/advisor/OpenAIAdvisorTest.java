package fr.ses10doigts.tradeIO5.service.tree.decision.advisor;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.MacdIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.RsiIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OpenAIAdvisorTest {

    @Autowired
    OpenAIAdvisor advisor;

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

        MarketContext mc = MarketContext.builder()
                .symbol("BTC")
                .indicatorValues(Map.of(
                        rsiKey, rsiR,
                        macdKey, macdR
                ))
                .lastPrice(BigDecimal.valueOf(98500))
                .build();

        DecisionContext dc = DecisionContext.builder()
                .marketContext(mc)
                .userProfile(null)
                .walletSnapshot(null)
                .build();

        LlmAdvice advise = advisor.advise(dc);

        System.out.println("Advice : "+advise);

        assertNotNull(advise);
        assertTrue(advise.isValid());
        assertNotNull(advise.getAction());
        assertTrue(advise.getConfidence() > 0.0);
        assertNotNull(advise.getRationale());
    }
}