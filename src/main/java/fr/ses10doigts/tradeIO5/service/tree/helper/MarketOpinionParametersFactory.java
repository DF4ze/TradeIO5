package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.RiskProfile;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;

import java.util.List;

public class MarketOpinionParametersFactory {

    public static MarketOpinionParameters buildRiskManagementParamWithDoubleRSI(
            Strategy strategy,
            StrategyParametersFactory.RsiParam slow,
            StrategyParametersFactory.RsiParam fast,
            RiskProfile profile
    ){
        StrategyParameters strategyParameters = StrategyParametersFactory.buildDoubleRsiStrategyParam(slow, fast);

        StrategyKey key = new StrategyKey(strategy, strategyParameters);

        return MarketOpinionParameters.builder()
                //.riskProfile(profile)
                .strategies(List.of(key))
                .build();
    }

    /**
     * Symétrique à {@link #buildRiskManagementParamWithDoubleRSI}, mais branche
     * {@link fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy}
     * (EMA + ADX + RSI) sur une {@code MarketOpinion} de scope {@code LOCAL}.
     */
    public static MarketOpinionParameters buildLocalOpinionParamWithTrendConfirmation(
            Strategy strategy,
            StrategyParametersFactory.TrendConfirmationParam param
    ){
        StrategyParameters strategyParameters = StrategyParametersFactory.buildTrendConfirmationStrategyParam(param);

        StrategyKey key = new StrategyKey(strategy, strategyParameters);

        return MarketOpinionParameters.builder()
                .strategies(List.of(key))
                .build();
    }
}
