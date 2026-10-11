package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.market.AssetSymbolValidator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. la demande de Clem (session du 2026-08-10, suite à la fermeture des étapes 6-7-8) : les
 * tools MCP ne doivent accepter qu'un symbole nu d'{@code Asset} (ex: {@code "BTC"}), jamais une
 * paire native d'exchange (ex: {@code "BTCUSDT"}), et doivent effectivement passer par la
 * résolution {@code asset_provider} — {@link AssetSymbolValidator} est le garde-fou ajouté à
 * cette fin. Unitaire pur (mocks), vérifie uniquement le branchement de la validation : le
 * comportement de résolution lui-même est couvert par {@code TreeAnalysisFacadeRealEntryPointsTest}
 * et {@code MarketDatasetEngineTest}.
 */
@DisplayName("TreeAnalysisMcpTools — validation du symbole (asset nu, pas une paire d'exchange)")
@ExtendWith(MockitoExtension.class)
class TreeAnalysisMcpToolsTest {

    @Mock
    private TreeAnalysisFacade treeAnalysisFacade;

    @Mock
    private StrategyRegistry strategyRegistry;

    @Mock
    private AssetSymbolValidator assetSymbolValidator;

    private TreeAnalysisMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new TreeAnalysisMcpTools(treeAnalysisFacade, strategyRegistry, assetSymbolValidator);
    }

    @Test
    @DisplayName("get_indicator : symbole rejeté par AssetSymbolValidator → JSON d'erreur, facade jamais appelée")
    void getIndicator_unknownSymbol_returnsErrorAndNeverCallsFacade() {
        doThrow(new IllegalArgumentException("Symbole d'actif inconnu : 'BTCUSDT'. Symboles connus : [BTC, ETH]"))
                .when(assetSymbolValidator).requireKnownAsset("BTCUSDT");

        String json = tools.getIndicator("BTCUSDT", TimeFrame.H1, IndicatorType.RSI, Map.of("period", 14.0), Map.of());

        assertTrue(json.contains("\"error\":true"));
        assertTrue(json.contains("Symbole d'actif inconnu"));
        verify(treeAnalysisFacade, never()).getIndicator(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("get_indicator : symbole nu connu → validé puis délégué à la façade")
    void getIndicator_knownSymbol_delegatesToFacade() {
        doNothing().when(assetSymbolValidator).requireKnownAsset("BTC");
        when(treeAnalysisFacade.getIndicator(eq("BTC"), eq(TimeFrame.H1), eq(IndicatorType.RSI), any(), any()))
                .thenReturn(IndicatorSnapshot.builder()
                        .indicatorType(IndicatorType.RSI)
                        .result(IndicatorResult.builder().valid(true).value(55.0).build())
                        .build());

        String json = tools.getIndicator("BTC", TimeFrame.H1, IndicatorType.RSI, Map.of("period", 14.0), Map.of());

        assertFalse(json.contains("\"error\":true"));
        assertTrue(json.contains("\"symbol\":\"BTC\""));
        verify(assetSymbolValidator).requireKnownAsset("BTC");
        verify(treeAnalysisFacade).getIndicator(eq("BTC"), eq(TimeFrame.H1), eq(IndicatorType.RSI), any(), any());
    }

    @Test
    @DisplayName("evaluate_strategy : symbole rejeté par AssetSymbolValidator → JSON d'erreur, facade jamais appelée")
    void evaluateStrategy_unknownSymbol_returnsErrorAndNeverCallsFacade() {
        doThrow(new IllegalArgumentException("Symbole d'actif inconnu : 'ETHUSDT'"))
                .when(assetSymbolValidator).requireKnownAsset("ETHUSDT");

        String json = tools.evaluateStrategy(
                "ETHUSDT", TimeFrame.H1, StrategyType.DIRECTIONAL, List.of(), Map.of(), Map.of(), Map.of());

        assertTrue(json.contains("\"error\":true"));
        assertTrue(json.contains("Symbole d'actif inconnu"));
        verify(treeAnalysisFacade, never()).evaluateStrategy(any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluate_strategy : symbole nu connu → validé puis délégué à la façade")
    void evaluateStrategy_knownSymbol_delegatesToFacade() {
        doNothing().when(assetSymbolValidator).requireKnownAsset("ETH");
        when(treeAnalysisFacade.evaluateStrategy(eq("ETH"), eq(TimeFrame.H1), eq(StrategyType.DIRECTIONAL), any()))
                .thenReturn(StrategySignal.builder()
                        .strategyName("TestStrategy")
                        .valid(true)
                        .type(SignalType.BULLISH)
                        .score(0.5)
                        .confidence(0.8)
                        .build());

        String json = tools.evaluateStrategy(
                "ETH", TimeFrame.H1, StrategyType.DIRECTIONAL, List.of(), Map.of(), Map.of(), Map.of());

        assertFalse(json.contains("\"error\":true"));
        verify(assetSymbolValidator).requireKnownAsset("ETH");
        verify(treeAnalysisFacade).evaluateStrategy(eq("ETH"), eq(TimeFrame.H1), eq(StrategyType.DIRECTIONAL), any());
    }

    @Test
    @DisplayName("get_opinion : symbole rejeté par AssetSymbolValidator → JSON d'erreur, facade jamais appelée")
    void getOpinion_unknownSymbol_returnsErrorAndNeverCallsFacade() {
        doThrow(new IllegalArgumentException("Symbole d'actif inconnu : 'SOLUSDT'"))
                .when(assetSymbolValidator).requireKnownAsset("SOLUSDT");

        String json = tools.getOpinion("SOLUSDT", OpinionScope.LOCAL, List.of());

        assertTrue(json.contains("\"error\":true"));
        assertTrue(json.contains("Symbole d'actif inconnu"));
        verify(treeAnalysisFacade, never()).getOpinion(any(), any(), any());
    }

    @Test
    @DisplayName("get_opinion : symbole nu connu → validé puis délégué à la façade")
    void getOpinion_knownSymbol_delegatesToFacade() {
        doNothing().when(assetSymbolValidator).requireKnownAsset("SOL");
        when(treeAnalysisFacade.getOpinion(eq("SOL"), eq(OpinionScope.LOCAL), any()))
                .thenReturn(new OpinionSignal(
                        "test-opinion", Optional.of("SOL"), SignalType.BULLISH, SignalType.BULLISH,
                        0.8, 0.5, OpinionScope.LOCAL, Set.of("test"), "test reason", Instant.now()
                ));

        String json = tools.getOpinion("SOL", OpinionScope.LOCAL, List.of());

        assertFalse(json.contains("\"error\":true"));
        verify(assetSymbolValidator).requireKnownAsset("SOL");
        verify(treeAnalysisFacade).getOpinion(eq("SOL"), eq(OpinionScope.LOCAL), any());
    }
}
