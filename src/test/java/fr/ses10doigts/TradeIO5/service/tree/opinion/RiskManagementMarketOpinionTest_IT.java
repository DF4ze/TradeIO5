package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.opinion.impl.RiskManagementMarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Decision - RiskManagement - IT")
@SpringBootTest
class RiskManagementMarketOpinionTest_IT {
    @Autowired
    private StrategyRegistry strategyRegistry;

    @Test
    void getRequiredCandles() {
        // Build params
        Strategy strategy = strategyRegistry.get(DoubleRsiStrategy.class.getSimpleName());
        StrategyParametersFactory.RsiParam fastRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.H1, 12, 70, 30);
        StrategyParametersFactory.RsiParam slowRsiParam = new StrategyParametersFactory.RsiParam(TimeFrame.M1, 24, 60, 28);

        MarketOpinionParameters marketOpinionParameters = MarketOpinionParametersFactory.buildRiskManagementParamWithDoubleRSI(strategy, slowRsiParam, fastRsiParam, RiskProfile.MEDIUM);

        RiskManagementMarketOpinion decision = new RiskManagementMarketOpinion();

        Map<TimeFrame, Integer> requiredCandles = decision.getRequiredCandles(marketOpinionParameters);

        assertNotNull(requiredCandles);
        assertEquals(12, requiredCandles.get(TimeFrame.H1));
        assertEquals(24, requiredCandles.get(TimeFrame.M1));
    }
}