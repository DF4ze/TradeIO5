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
        // Build params : EMA rapide=10, EMA lente=20, ADX=14, RSI=14, tous sur H1.
        Strategy strategy = strategyRegistry.get(TrendConfirmationStrategy.class.getSimpleName());
        StrategyParametersFactory.TrendConfirmationParam param = new StrategyParametersFactory.TrendConfirmationParam(
                TimeFrame.H1, 10, 20, 14, 14,
                15.0, 25.0,
                80.0, 20.0
        );

        MarketOpinionParameters marketOpinionParameters =
                MarketOpinionParametersFactory.buildLocalOpinionParamWithTrendConfirmation(strategy, param);

        DefaultMarketOpinion decision = new DefaultMarketOpinion();

        Map<TimeFrame, Integer> requiredCandles = decision.getRequiredCandles(marketOpinionParameters);

        assertNotNull(requiredCandles);
        // Les 4 indicateurs partagent le même TimeFrame (H1) : AdxIndicator.getRequiredData()
        // renvoie 2 x period (28), le plus exigeant des 4 (EMA fast=10, EMA slow=20, RSI=14).
        assertEquals(28, requiredCandles.get(TimeFrame.H1));
    }
}