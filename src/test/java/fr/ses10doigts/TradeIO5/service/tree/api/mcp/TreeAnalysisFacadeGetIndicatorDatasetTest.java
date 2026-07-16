package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinionRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etude-cache-etf-flow-historisation.md, correctif repéré par Clem le 2026-07-16 en
 * observant les logs d'un simple {@code get_indicator ETF_FLOW} : {@code TreeAnalysisFacade#getIndicator}
 * appelait systématiquement {@link MarketDatasetEngine#getDataset} (jusqu'à 500 D1, potentiellement
 * un vrai appel réseau Binance si le cache DB était incomplet), même pour les indicateurs qui
 * déclarent {@code getRequiredData() == 0} (ETF_FLOW, FEAR_GREED, DXY, SP500, NASDAQ,
 * STABLECOIN_MARKET_CAP, OPEN_INTEREST, FUNDING_RATE, LIQUIDATIONS, ORDER_BOOK) et ne lisent jamais
 * {@code IndicatorContext.marketDataset()}. Unitaire pur (mocks), pas de contexte Spring : plus
 * rapide et isolé de la disponibilité réelle d'un indicateur getRequiredData()==0 dans le registry.
 */
@DisplayName("TreeAnalysisFacade.getIndicator — dataset skip quand getRequiredData() == 0")
class TreeAnalysisFacadeGetIndicatorDatasetTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final ApiCredentialDTO CREDENTIAL = null;

    private MarketDatasetEngine marketDatasetEngine;
    private IndicatorRegistry indicatorRegistry;
    private TreeAnalysisFacade facade;

    @BeforeEach
    void setUp() {
        marketDatasetEngine = mock(MarketDatasetEngine.class);
        IndicatorEngine indicatorEngine = mock(IndicatorEngine.class);
        indicatorRegistry = mock(IndicatorRegistry.class);
        IndicatorCredentialResolver credentialResolver = mock(IndicatorCredentialResolver.class);
        when(credentialResolver.resolve(any())).thenReturn(CREDENTIAL);

        DomainClock clock = new FixedDomainClock(Instant.parse("2026-07-16T12:00:00Z"));
        MarketDataApiClient binanceClient = mock(MarketDataApiClient.class);

        facade = new TreeAnalysisFacade(
                marketDatasetEngine,
                indicatorEngine,
                indicatorRegistry,
                mock(StrategyRegistry.class),
                mock(MarketOpinionRegistry.class),
                mock(EventBus.class),
                clock,
                binanceClient,
                credentialResolver
        );

        when(indicatorEngine.execute(any(), any())).thenReturn(
                IndicatorSnapshot.builder().indicatorType(IndicatorType.ETF_FLOW)
                        .result(IndicatorResult.builder().valid(true).value(1.0).build())
                        .build()
        );
    }

    @Test
    @DisplayName("getRequiredData() == 0 : aucun appel à MarketDatasetEngine.getDataset (ex: ETF_FLOW)")
    void getIndicator_zeroRequiredData_skipsDatasetFetch() {
        Indicator zeroRequirementIndicator = mock(Indicator.class);
        when(zeroRequirementIndicator.getType()).thenReturn(IndicatorType.ETF_FLOW);
        when(zeroRequirementIndicator.getRequiredData(any())).thenReturn(0);
        when(indicatorRegistry.contains(IndicatorType.ETF_FLOW)).thenReturn(true);
        when(indicatorRegistry.get(IndicatorType.ETF_FLOW)).thenReturn(zeroRequirementIndicator);

        facade.getIndicator(SYMBOL, TimeFrame.D1, IndicatorType.ETF_FLOW, Map.of(), Map.of("asset", "BTC"));

        verify(marketDatasetEngine, never()).getDataset(any());
    }

    @Test
    @DisplayName("getRequiredData() != 0 : MarketDatasetEngine.getDataset toujours appelé (ex: RSI)")
    void getIndicator_nonZeroRequiredData_stillFetchesDataset() {
        Indicator candleBasedIndicator = mock(Indicator.class);
        when(candleBasedIndicator.getType()).thenReturn(IndicatorType.RSI);
        when(candleBasedIndicator.getRequiredData(any())).thenReturn(14);
        when(indicatorRegistry.contains(IndicatorType.RSI)).thenReturn(true);
        when(indicatorRegistry.get(IndicatorType.RSI)).thenReturn(candleBasedIndicator);
        when(marketDatasetEngine.getDataset(any())).thenReturn(
                fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset.builder()
                        .pair(SYMBOL).timeFrame(TimeFrame.H1).marketDatas(java.util.List.of()).build()
        );

        facade.getIndicator(SYMBOL, TimeFrame.H1, IndicatorType.RSI, Map.of("period", 14.0), Map.of());

        verify(marketDatasetEngine).getDataset(any());
    }
}
