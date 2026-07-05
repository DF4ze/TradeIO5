package fr.ses10doigts.tradeIO5.service.tree.api.mcp;

import fr.ses10doigts.tradeIO5.exceptions.TreeAnalysisException;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.RiskProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
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
        Strategy strategy = strategyRegistry.get(DoubleRsiStrategy.class.getSimpleName());

        StrategyParametersFactory.RsiParam slowRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.H12, 24, 60, 28);
        StrategyParametersFactory.RsiParam fastRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.H1, 12, 70, 30);

        MarketOpinionParameters params = MarketOpinionParametersFactory.buildRiskManagementParamWithDoubleRSI(
                strategy, slowRsiParam, fastRsiParam, RiskProfile.MEDIUM
        );

        OpinionSignal signal = treeAnalysisFacade.getOpinion(
                "mcpFacadeOpinion",
                OpinionScope.MACRO,
                params,
                MarketDataSource.MEMORY,
                TrendType.UPTREND
        );

        assertNotNull(signal);
        assertNotNull(signal.opinionId());
        assertNotNull(signal.weightedSignal());
        assertNotNull(signal.majoritySignal());
        assertNotNull(signal.timestamp());
        assertEquals(OpinionScope.MACRO, signal.scope());
    }

    @Test
    @DisplayName("evaluateStrategy avec un StrategyType non enregistré renvoie une erreur claire")
    void evaluateStrategy_unknownStrategyType_shouldThrowClearError() {
        // Aucune Strategy enregistrée aujourd'hui ne couvre StrategyType.RISK (seule
        // DoubleRsiStrategy existe, sur ENTRY/EXIT).
        StrategyParameters params = new StrategyParameters();

        TreeAnalysisException ex = assertThrows(TreeAnalysisException.class, () ->
                treeAnalysisFacade.evaluateStrategy(
                        "mcpFacadeUnknownStrategy",
                        TimeFrame.H1,
                        StrategyType.RISK,
                        params,
                        MarketDataSource.MEMORY,
                        TrendType.FLAT
                )
        );

        assertTrue(ex.getMessage().contains("RISK"));
    }

    @Test
    @DisplayName("getOpinion avec un OpinionScope non enregistré renvoie une erreur claire")
    void getOpinion_unknownScope_shouldThrowClearError() {
        // Aucune MarketOpinion enregistrée aujourd'hui ne couvre OpinionScope.LOCAL (seule
        // DefaultMarketOpinion existe, sur MACRO).
        MarketOpinionParameters params = MarketOpinionParameters.builder().build();

        TreeAnalysisException ex = assertThrows(TreeAnalysisException.class, () ->
                treeAnalysisFacade.getOpinion(
                        "mcpFacadeUnknownScope",
                        OpinionScope.LOCAL,
                        params,
                        MarketDataSource.MEMORY,
                        TrendType.FLAT
                )
        );

        assertTrue(ex.getMessage().contains("LOCAL"));
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
