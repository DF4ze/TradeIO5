package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.StablecoinMarketCapIndicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vérifie l'enrichissement STABLECOIN_MARKET_CAP ajouté par l'étude
 * "nouvelles-opinions-indicateurs-non-branches" §3 : combinaison avec Fear&amp;Greed (poids
 * minoritaire), et repli propre sur 100% Fear&amp;Greed quand l'indicateur stablecoin est
 * indisponible.
 * <p>
 * {@code eventBus} est un champ {@code @Autowired} (pas de constructeur), injecté par réflexion ici
 * comme les autres tests de ce package n'ont pas encore eu à le faire (aucun test dédié à
 * {@code GlobalMarketOpinion} n'existait avant cette étude).
 */
@DisplayName("GlobalMarketOpinion - Fear&Greed + STABLECOIN_MARKET_CAP")
class GlobalMarketOpinionTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    private IndicatorEngine indicatorEngine;
    private EventBus eventBus;
    private GlobalMarketOpinion opinion;

    @BeforeEach
    void setUp() throws Exception {
        indicatorEngine = mock(IndicatorEngine.class);
        IndicatorCredentialResolver credentialResolver = mock(IndicatorCredentialResolver.class);
        eventBus = mock(EventBus.class);

        opinion = new GlobalMarketOpinion(indicatorEngine, credentialResolver);
        Field field = GlobalMarketOpinion.class.getDeclaredField("eventBus");
        field.setAccessible(true);
        field.set(opinion, eventBus);
    }

    @Test
    @DisplayName("Fear&Greed neutre (50) + stablecoin en forte croissance => score combiné tiré vers le haussier")
    void decide_blendsStablecoinScore_whenAvailable() {
        mockIndicator(IndicatorType.FEAR_GREED, Map.of("now", 50.0, "yesterday", 50.0));
        mockIndicator(IndicatorType.STABLECOIN_MARKET_CAP, Map.of(
                StablecoinMarketCapIndicator.V_TOTAL, 103.0,
                StablecoinMarketCapIndicator.V_TOTAL_PREV_WEEK, 100.0,
                StablecoinMarketCapIndicator.V_TOTAL_PREV_DAY, 102.0,
                StablecoinMarketCapIndicator.V_TOTAL_PREV_MONTH, 95.0
        ));

        opinion.decide(context(), MarketOpinionParameters.builder().build());

        ArgumentCaptor<OpinionEvent> captor = ArgumentCaptor.forClass(OpinionEvent.class);
        verify(eventBus).publish(captor.capture());

        // Fear&Greed(50) -> score neutre proche de 0 (milieu de la plage HOLD [25,75]). +3%
        // hebdo stablecoin (échelle par défaut 3%) -> stablecoinScore = 1.0. Poids par défaut 40% :
        // combinedScore = 0.6*0 + 0.4*1.0 = 0.4, strictement positif.
        assertTrue(captor.getValue().getScore() > 0.0,
                "le score combiné doit être tiré vers le haussier par le stablecoin");
        assertTrue(captor.getValue().getSources().contains("STABLECOIN_MARKET_CAP"));
    }

    @Test
    @DisplayName("STABLECOIN_MARKET_CAP invalide => repli propre sur 100% Fear&Greed, pas d'exception")
    void decide_fallsBackToPureFearGreed_whenStablecoinInvalid() {
        mockIndicator(IndicatorType.FEAR_GREED, Map.of("now", 15.0, "yesterday", 15.0));
        when(indicatorEngine.execute(any(), argThatType(IndicatorType.STABLECOIN_MARKET_CAP)))
                .thenReturn(IndicatorSnapshot.builder().result(IndicatorResult.invalid()).build());

        opinion.decide(context(), MarketOpinionParameters.builder().build());

        ArgumentCaptor<OpinionEvent> captor = ArgumentCaptor.forClass(OpinionEvent.class);
        verify(eventBus).publish(captor.capture());

        assertEquals(1, captor.getValue().getSources().size());
        assertTrue(captor.getValue().getSources().contains("FEAR_GREED"));
    }

    private void mockIndicator(IndicatorType type, Map<String, Double> values) {
        when(indicatorEngine.execute(any(), argThatType(type)))
                .thenReturn(IndicatorSnapshot.builder()
                        .result(IndicatorResult.builder().valid(true).values(values).build())
                        .build());
    }

    private static IndicatorParameters argThatType(IndicatorType type) {
        return org.mockito.ArgumentMatchers.argThat(p -> p != null && p.getIndicatorType() == type);
    }

    private OpinionContext context() {
        MarketContext mc = new MarketContext(null, BigDecimal.ZERO, new FixedDomainClock(NOW), Map.of(), Map.of());
        return new OpinionContext(null, null, mc, Map.of(), new FixedDomainClock(NOW));
    }
}
