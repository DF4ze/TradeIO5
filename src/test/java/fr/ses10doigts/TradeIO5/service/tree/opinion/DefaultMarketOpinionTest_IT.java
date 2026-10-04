package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.opinion.impl.DefaultMarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Decision - RiskManagement - IT")
@SpringBootTest
class DefaultMarketOpinionTest_IT {
    @Autowired
    private StrategyRegistry strategyRegistry;

    @Test
    void getRequiredCandles() {
        // Build params : 3x LINEAR_REGRESSION (periodes 7/14/30) sur H1, cf. réécriture Étape 8
        // (régression à hystérésis, remplace SWING_STRUCTURE + ADX).
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParametersFactory.TrendConfirmationParam param =
                StrategyParametersFactory.TrendConfirmationParam.defaults(TimeFrame.H1);

        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        DefaultMarketOpinion decision = new DefaultMarketOpinion();

        Map<TimeFrame, Integer> requiredCandles = decision.getRequiredCandles(marketOpinionParameters);

        assertNotNull(requiredCandles);
        // TrendConfirmationStrategy#getRequiredCandles impose désormais TrendAnalyzer.MIN_CANDLES
        // (60 = 30 fenêtre longue + 30 warmup hystérésis) sur le TimeFrame de ses indicateurs.
        assertEquals(60, requiredCandles.get(TimeFrame.H1));
    }
}