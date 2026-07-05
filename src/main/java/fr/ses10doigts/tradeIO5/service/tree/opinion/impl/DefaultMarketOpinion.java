package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.opinion.AbstractMarketOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DefaultMarketOpinion extends AbstractMarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(DefaultMarketOpinion.class);

    @Autowired
    private EventBus eventBus;

    @Override
    public OpinionScope getScope() {
        return OpinionScope.LOCAL;
    }

    @Override
    protected void interpretSignals(
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
        logger.debug("weightedSignal: {} at {}", weightedSignal.weightedSignal, weightedSignal.confidence);



        MarketAction action = resolveAction(weightedScore, majoritySignal, riskProfile);
        logger.debug("action: {}", action);

 */
        Optional<String> symbol = Optional.empty();
        if( context.marketContext() != null && context.marketContext().symbol() != null ){
            symbol = Optional.of(context.marketContext().symbol());
        }

        Set<String> sources = signals.stream()
                .map(StrategySignal::getReason)
                .collect(Collectors.toSet());

        OpinionEvent event = new OpinionEvent( new OpinionSignal(
                getId(),
                symbol,
                majoritySignal,
                confidenceSignal.signal,
                confidenceSignal.confidence,
                weightedScore,
                getScope(),
                sources,
                "Reason",
                context.clock().now()
        ));

        eventBus.publish(event);


    }


}
