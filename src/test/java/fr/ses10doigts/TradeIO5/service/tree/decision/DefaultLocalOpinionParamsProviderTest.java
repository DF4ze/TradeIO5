package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.EtfFlowConfidenceStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.MovementQualificationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.OrderFlowStrategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Couvre l'extraction (2026-08-17, plan de test manuel Palier 3) de la combinaison de strategies
 * par défaut hors de {@link DecisionOrchestrator} : garantit que build() produit bien les 4
 * strategies attendues (TrendConfirmation + MovementQualification + OrderFlow + EtfFlow), aucun test
 * existant ne vérifiait ce contenu auparavant (DecisionOrchestratorTest stubait
 * {@code facade.getOpinion(..., any())}, sans jamais inspecter les params réels).
 */
@DisplayName("DefaultLocalOpinionParamsProvider")
class DefaultLocalOpinionParamsProviderTest {

    private IndicatorCredentialResolver credentialResolver;
    private TrendConfirmationStrategy trendConfirmationStrategy;
    private MovementQualificationStrategy movementQualificationStrategy;
    private OrderFlowStrategy orderFlowStrategy;
    private EtfFlowConfidenceStrategy etfFlowConfidenceStrategy;
    private DefaultLocalOpinionParamsProvider provider;

    @BeforeEach
    void setUp() {
        credentialResolver = mock(IndicatorCredentialResolver.class);
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.COINALYZE, "key", "secret", "https://example.test");
        when(credentialResolver.resolve(IndicatorType.OPEN_INTEREST)).thenReturn(credential);
        when(credentialResolver.resolve(IndicatorType.LIQUIDATIONS)).thenReturn(credential);
        when(credentialResolver.resolve(IndicatorType.ETF_FLOW)).thenReturn(credential);

        trendConfirmationStrategy = mock(TrendConfirmationStrategy.class);
        movementQualificationStrategy = mock(MovementQualificationStrategy.class);
        orderFlowStrategy = mock(OrderFlowStrategy.class);
        etfFlowConfidenceStrategy = mock(EtfFlowConfidenceStrategy.class);

        provider = new DefaultLocalOpinionParamsProvider(
                credentialResolver, trendConfirmationStrategy, movementQualificationStrategy,
                orderFlowStrategy, etfFlowConfidenceStrategy);
    }

    @Test
    @DisplayName("build() combine exactement les 4 strategies attendues (Trend/MovementQualification/OrderFlow/EtfFlow)")
    void build_combinesExactlyFourStrategies() {
        MarketOpinionParameters params = provider.build();

        List<StrategyKey> keys = params.getStrategies();
        assertEquals(4, keys.size());

        Set<Object> strategiesUsed = keys.stream().map(StrategyKey::getStrategy).collect(java.util.stream.Collectors.toSet());
        assertTrue(strategiesUsed.contains(trendConfirmationStrategy));
        assertTrue(strategiesUsed.contains(movementQualificationStrategy));
        assertTrue(strategiesUsed.contains(orderFlowStrategy));
        assertTrue(strategiesUsed.contains(etfFlowConfidenceStrategy));
    }

    @Test
    @DisplayName("build() résout les 3 credentials externes attendus (OPEN_INTEREST, LIQUIDATIONS, ETF_FLOW)")
    void build_resolvesExpectedCredentials() {
        provider.build();

        org.mockito.Mockito.verify(credentialResolver).resolve(IndicatorType.OPEN_INTEREST);
        org.mockito.Mockito.verify(credentialResolver).resolve(IndicatorType.LIQUIDATIONS);
        org.mockito.Mockito.verify(credentialResolver).resolve(IndicatorType.ETF_FLOW);
    }
}
