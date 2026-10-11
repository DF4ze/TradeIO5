package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinionRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etudes/etude-fallback-multi-provider-marketdata.md §3 (étape 8a) : les 3 points d'entrée
 * réels (utilisés par les tools MCP) doivent désormais résoudre le dataset via
 * {@link MarketDatasetEngine#getDatasetForAsset} (asset_provider) et non plus via
 * {@link MarketDatasetEngine#getDataset} avec {@code MarketDataSource.BINANCE} codé en dur.
 * Complète {@code TreeAnalysisFacadeGetIndicatorDatasetTest} (qui couvre déjà {@code getIndicator})
 * pour {@code evaluateStrategy} et {@code getOpinion}. Unitaire pur (mocks + un {@link EventBus}
 * réel, léger et sans dépendance), pas de contexte Spring.
 */
@DisplayName("TreeAnalysisFacade — points d'entrée réels résolus via asset_provider")
class TreeAnalysisFacadeRealEntryPointsTest {

    private static final String SYMBOL = "BTC";
    private static final ApiCredentialDTO CREDENTIAL = null;

    private MarketDatasetEngine marketDatasetEngine;
    private StrategyRegistry strategyRegistry;
    private MarketOpinionRegistry marketOpinionRegistry;
    private EventBus eventBus;
    private TreeAnalysisFacade facade;

    @BeforeEach
    void setUp() {
        marketDatasetEngine = mock(MarketDatasetEngine.class);
        IndicatorEngine indicatorEngine = mock(IndicatorEngine.class);
        IndicatorRegistry indicatorRegistry = mock(IndicatorRegistry.class);
        strategyRegistry = mock(StrategyRegistry.class);
        marketOpinionRegistry = mock(MarketOpinionRegistry.class);
        eventBus = new EventBus();
        IndicatorCredentialResolver credentialResolver = mock(IndicatorCredentialResolver.class);
        when(credentialResolver.resolve(any())).thenReturn(CREDENTIAL);

        DomainClock clock = new FixedDomainClock(Instant.parse("2026-08-10T12:00:00Z"));

        facade = new TreeAnalysisFacade(
                marketDatasetEngine,
                indicatorEngine,
                indicatorRegistry,
                strategyRegistry,
                marketOpinionRegistry,
                eventBus,
                clock,
                credentialResolver
        );

        when(marketDatasetEngine.getDatasetForAsset(any(), any(), anyInt(), any())).thenReturn(
                MarketDataset.builder().pair(SYMBOL).timeFrame(TimeFrame.H1).marketDatas(List.of()).build()
        );
    }

    @Test
    @DisplayName("evaluateStrategy(4 args, point d'entrée réel) résout via MarketDatasetEngine.getDatasetForAsset")
    void evaluateStrategy_realEntryPoint_resolvesViaGetDatasetForAsset() {
        Strategy strategy = mock(Strategy.class);
        StrategySignal expectedSignal = mock(StrategySignal.class);
        when(strategy.getRequiredCandles(any())).thenReturn(Map.of());
        when(strategy.evaluate(any(), any())).thenReturn(expectedSignal);
        when(strategyRegistry.resolveBestMatch(eq(StrategyType.DIRECTIONAL), any())).thenReturn(strategy);

        StrategyParameters params = new StrategyParameters();

        StrategySignal result = facade.evaluateStrategy(SYMBOL, TimeFrame.H1, StrategyType.DIRECTIONAL, params);

        assertNotNull(result);
        verify(marketDatasetEngine).getDatasetForAsset(eq(SYMBOL), eq(TimeFrame.H1), anyInt(), any());
        verify(marketDatasetEngine, never()).getDataset(any());
    }

    @Test
    @DisplayName("getOpinion(3 args, point d'entrée réel) résout via MarketDatasetEngine.getDatasetForAsset")
    void getOpinion_realEntryPoint_resolvesViaGetDatasetForAsset() {
        MarketOpinion opinion = mock(MarketOpinion.class);
        when(opinion.getRequiredCandles(any())).thenReturn(Map.of(TimeFrame.H1, 500));
        when(marketOpinionRegistry.get(OpinionScope.LOCAL)).thenReturn(List.of(opinion));

        // decide(...) doit émettre un OpinionEvent (contrat de MarketOpinion) : on le simule ici
        // via le vrai EventBus injecté dans la façade, comme le ferait une implémentation réelle.
        doAnswer(invocation -> {
            OpinionSignal signal = new OpinionSignal(
                    "test-opinion", Optional.of(SYMBOL), SignalType.BULLISH, SignalType.BULLISH,
                    0.8, 0.5, OpinionScope.LOCAL, Set.of("test"), "test reason", Instant.now()
            );
            eventBus.publish(new OpinionEvent(signal));
            return null;
        }).when(opinion).decide(any(), any());

        MarketOpinionParameters params = MarketOpinionParameters.builder().build();

        OpinionSignal signal = facade.getOpinion(SYMBOL, OpinionScope.LOCAL, params);

        assertNotNull(signal);
        verify(marketDatasetEngine).getDatasetForAsset(eq(SYMBOL), eq(TimeFrame.H1), anyInt(), any());
        verify(marketDatasetEngine, never()).getDataset(any());
    }
}
