package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.exceptions.TreeAnalysisException;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie que {@link TreeAnalysisFacade} déclenche réellement toute la chaîne
 * candles → indicateurs → stratégie → opinion (source {@link MarketDataSource#MEMORY}, comme
 * le reste de la base de tests, cf. {@code DefaultMarketOpinionTest_UT}), et que les entrées
 * inconnues (StrategyType/OpinionScope non enregistrés, symbole invalide) renvoient une erreur
 * propre plutôt qu'une exception non gérée.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("TreeAnalysisFacade")
@SpringBootTest
class TreeAnalysisFacadeTest {

    @Autowired
    private TreeAnalysisFacade treeAnalysisFacade;
    @Autowired
    private StrategyRegistry strategyRegistry;

    @Test
    @DisplayName("getIndicator sur source MEMORY calcule bien un RSI valide")
    void getIndicator_onMemorySource_shouldReturnValidSnapshot() {
        IndicatorSnapshot snapshot = treeAnalysisFacade.getIndicator(
                "mcpFacadeIndicator",
                TimeFrame.H1,
                IndicatorType.RSI,
                Map.of("period", 14.0),
                MarketDataSource.MEMORY,
                TrendType.UPTREND
        );

        assertNotNull(snapshot);
        assertEquals(IndicatorType.RSI, snapshot.getIndicatorType());
        assertTrue(snapshot.getResult().isValid());
    }

    @Test
    @DisplayName("getOpinion sur source MEMORY déclenche toute la chaîne et retourne un OpinionSignal valide")
    void getOpinion_onMemorySource_shouldReturnValidOpinionSignal() {
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());

        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TimeFrame.H1, 10, 20, 14, 14,
                15.0, 25.0,
                80.0, 20.0
        );

        MarketOpinionParameters params = MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(
                strategy, param
        );

        OpinionSignal signal = treeAnalysisFacade.getOpinion(
                "mcpFacadeOpinion",
                OpinionScope.LOCAL,
                params,
                MarketDataSource.MEMORY,
                TrendType.UPTREND
        );

        assertNotNull(signal);
        assertNotNull(signal.opinionId());
        assertNotNull(signal.weightedSignal());
        assertNotNull(signal.majoritySignal());
        assertNotNull(signal.timestamp());
        assertEquals(OpinionScope.LOCAL, signal.scope());
    }

    @Test
    @DisplayName("evaluateStrategy avec un StrategyType null renvoie une erreur claire")
    void evaluateStrategy_nullStrategyType_shouldThrowClearError() {
        // Depuis la fusion ENTRY/EXIT/RISK -> DIRECTIONAL (StrategyType n'a plus que DIRECTIONAL et
        // CONFIDENCE_MODULATOR, chacun couvert par au moins une Strategy enregistrée), il n'existe
        // plus de valeur d'enum "connue mais non enregistrée" pour déclencher l'erreur "No Strategy
        // registered for type". Le seul cas d'erreur claire restant côté StrategyType est un type
        // manquant (cf. TreeAnalysisFacade#resolveStrategy).
        StrategyParameters params = new StrategyParameters();

        TreeAnalysisException ex = assertThrows(TreeAnalysisException.class, () ->
                treeAnalysisFacade.evaluateStrategy(
                        "mcpFacadeUnknownStrategy",
                        TimeFrame.H1,
                        null,
                        params,
                        MarketDataSource.MEMORY,
                        TrendType.FLAT
                )
        );

        assertTrue(ex.getMessage().contains("StrategyType"));
    }

    @Test
    @DisplayName("getOpinion sur MACRO sans credential Twelve Data en test renvoie une erreur claire (aucun OpinionEvent émis)")
    void getOpinion_macroWithoutCredentials_shouldThrowClearError() {
        // Depuis MacroMarketOpinion (étude "nouvelles-opinions-indicateurs-non-branches" §2), les
        // 4 OpinionScope ont désormais tous une MarketOpinion enregistrée -> ce test ne peut plus
        // vérifier "aucune MarketOpinion pour ce scope" (résolution de scope, plus de scope
        // "orphelin" disponible dans l'enum). Il vérifie à la place le comportement en aval :
        // MacroMarketOpinion ne publie aucun OpinionEvent si DXY est invalid (pas de credential
        // Twelve Data configurée dans application-test.properties), ce que la façade doit remonter
        // comme une TreeAnalysisException claire plutôt qu'un throw silencieux ailleurs — même
        // contrat vérifié pour les autres cas d'erreur de cette classe.
        MarketOpinionParameters params = MarketOpinionParameters.builder().build();

        TreeAnalysisException ex = assertThrows(TreeAnalysisException.class, () ->
                treeAnalysisFacade.getOpinion(
                        "mcpFacadeMacroNoCredentials",
                        OpinionScope.MACRO,
                        params,
                        MarketDataSource.MEMORY,
                        TrendType.FLAT
                )
        );

        assertTrue(ex.getMessage().contains("did not emit"));
    }

    @Test
    @DisplayName("Un symbole vide renvoie une erreur claire plutôt qu'une NullPointerException")
    void getIndicator_blankSymbol_shouldThrowClearError() {
        TreeAnalysisException ex = assertThrows(TreeAnalysisException.class, () ->
                treeAnalysisFacade.getIndicator(
                        " ",
                        TimeFrame.H1,
                        IndicatorType.RSI,
                        Map.of("period", 14.0),
                        MarketDataSource.MEMORY,
                        TrendType.UPTREND
                )
        );

        assertTrue(ex.getMessage().toLowerCase().contains("symbol"));
    }
}
