package fr.ses10doigts.tradeIO5.service.tree.opinion.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.connector.OpenAIService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.RsiIndicator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Non-régression : maintenant que OpenAIService#ask exige un LlmTier explicite (plus de modèle
 * par défaut implicite), OpenAIAdvisor doit demander le niveau MEDIUM (choix explicite validé,
 * cf. docs/prompt-implementation-llm-model-tiers.md).
 */
@DisplayName("OpenAIAdvisor - niveau LLM demandé")
class OpenAIAdvisorTierTest {

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        clock = new FixedDomainClock(Instant.parse("2025-01-01T12:00:00Z"));
    }

    @Test
    @DisplayName("callModel demande explicitement le niveau MEDIUM")
    void callModel_requestsMediumTier() {
        OpenAIService service = mock(OpenAIService.class);
        LlmAdvice expected = LlmAdvice.invalid();
        when(service.ask(anyString(), eq(LlmTier.MEDIUM))).thenReturn(expected);

        OpenAIAdvisor advisor = new OpenAIAdvisor(service);

        IndicatorParameters rsiP = IndicatorParameters.builder()
                .indicatorType(IndicatorType.RSI)
                .numerics(Map.of(RsiIndicator.P_PERIOD_NAME, 14.0))
                .build();
        IndicatorResult rsiR = IndicatorResult.builder().value(35.0).valid(true).build();
        IndicatorKey rsiKey = new IndicatorKey(IndicatorType.RSI, TimeFrame.H1, rsiP);

        MarketContext mc = new MarketContext(
                "BTCUSDT",
                BigDecimal.valueOf(98500),
                clock,
                Map.of(),
                Map.of(rsiKey, rsiR)
        );

        OpinionContext ctx = new OpinionContext(null, null, mc, Map.of(), clock);

        advisor.advise(ctx);

        verify(service).ask(anyString(), eq(LlmTier.MEDIUM));
    }
}
