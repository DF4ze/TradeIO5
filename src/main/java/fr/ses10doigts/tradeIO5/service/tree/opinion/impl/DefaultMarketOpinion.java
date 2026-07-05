package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.opinion.AbstractMarketOpinion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
            OpinionContext context,
            MarketOpinionParameters parameters,
            AggregatedStrategySignal aggregatedSignal
    ) {
        for (StrategySignal signal : aggregatedSignal.getSignals()){
            logger.debug("s:{}, t:{}, c:{}", signal.getScore(), signal.getType(), signal.getConfidence());
        }

        Optional<String> symbol = Optional.empty();
        if( context.marketContext() != null && context.marketContext().symbol() != null ){
            symbol = Optional.of(context.marketContext().symbol());
        }

        Set<String> sources = aggregatedSignal.getSignals().stream()
                .map(StrategySignal::getReason)
                .collect(Collectors.toSet());

        OpinionEvent event = new OpinionEvent( new OpinionSignal(
                getId(),
                symbol,
                aggregatedSignal.getFinalSignal(),
                aggregatedSignal.getFinalSignal(),
                aggregatedSignal.getConfidence(),
                aggregatedSignal.getScore(),
                getScope(),
                sources,
                aggregatedSignal.getExplanation(),
                context.clock().now()
        ));

        eventBus.publish(event);


    }


}
