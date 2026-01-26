package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.OpinionType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.tree.opinion.AbstractMarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Décision chargée d'adapter le comportement global
 * selon le niveau de risque utilisateur.
 */
@Component
public class RiskManagementMarketOpinion extends AbstractMarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(RiskManagementMarketOpinion.class);

    @Override
    public OpinionType getType() {
        return OpinionType.MACRO;
    }


    @Override
    protected MarketOpinionResult interpretSignals(
            List<StrategySignal> signals,
            OpinionContext context,
            MarketOpinionParameters parameters,
            double weightedScore,
            SignalType majoritySignal,
            MarketOpinionHelper.ConfidenceSignal confidenceSignal
    ) {
        for (StrategySignal signal : signals){
            logger.debug("s:{}, t:{}, c:{}", signal.getScore(), signal.getType(), signal.getConfidence());
        }

/*        RiskProfile riskProfile = parameters.getRiskProfile();
        logger.debug("Profil: {}", riskProfile);



        MarketOpinionHelper.ConfidenceSignal weightedSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(weightedScore);
        logger.debug("weightedSignal: {} at {}", weightedSignal.signal, weightedSignal.confidence);



        MarketAction action = resolveAction(weightedScore, majoritySignal, riskProfile);
        logger.debug("action: {}", action);

 */


        return new MarketOpinionResult(
                majoritySignal,
                confidenceSignal.signal,
                confidenceSignal.confidence,
                weightedScore,
                signals,
                "WeightedScore: "+ weightedScore+", confSignal: "+confidenceSignal+", majoritySignal: "+majoritySignal
        );
    }


}
