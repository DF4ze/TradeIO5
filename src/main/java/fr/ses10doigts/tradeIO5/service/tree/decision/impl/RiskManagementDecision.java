package fr.ses10doigts.tradeIO5.service.tree.decision.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionResult;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionAction;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.tree.decision.AbstractDecision;
import fr.ses10doigts.tradeIO5.service.tree.helper.DecisionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Décision chargée d'adapter le comportement global
 * selon le niveau de risque utilisateur.
 */
@Component
public class RiskManagementDecision extends AbstractDecision {
    private final Logger logger = LoggerFactory.getLogger(RiskManagementDecision.class);

    @Override
    public DecisionType getType() {
        return DecisionType.RISK_MANAGEMENT;
    }

    @Override
    protected DecisionResult interpretSignals(
            List<StrategySignal> signals,
            DecisionContext context,
            DecisionParameters parameters
    ) {
        for (StrategySignal signal : signals){
            logger.debug("s:{}, t:{}, c:{}", signal.getScore(), signal.getType(), signal.getConfidence());
        }

        RiskProfile riskProfile = parameters.getRiskProfile();
        logger.debug("Profil: {}", riskProfile);

        double weightedScore = calculateWeightedScore(signals);
        logger.debug("weightedScore: {}", weightedScore);

        DecisionHelper.ConfidenceSignal weightedSignal = DecisionHelper.scoreToConfidenceAndSignalType(weightedScore);
        logger.debug("weightedSignal: {} at {}", weightedSignal.signal, weightedSignal.confidence);

        SignalType majoritySignal = determineMajoritySignal(signals);
        logger.debug("majoritySignal: {}", majoritySignal);

        DecisionAction action = resolveAction(weightedScore, majoritySignal, riskProfile);
        logger.debug("action: {}", action);

        return buildDecisionResult(action, weightedScore, signals, riskProfile);
    }


}
