package fr.ses10doigts.tradeIO5.service.decision.helper;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import fr.ses10doigts.tradeIO5.service.decision.strategy.Strategy;

import java.util.List;

public class DecisionParametersFactory {

    public static DecisionParameters buildRiskManagementParamWithDoubleRSI(
            Strategy strategy,
            StrategyParametersFactory.RsiParam slow,
            StrategyParametersFactory.RsiParam fast,
            RiskProfile profile
    ){
        StrategyParameters strategyParameters = StrategyParametersFactory.buildDoubleRsiStrategyParam(slow, fast);

        StrategyKey key = new StrategyKey(strategy, strategyParameters);

        return DecisionParameters.builder()
                .riskProfile(profile)
                .strategies(List.of(key))
                .build();
    }
}
