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

/**
 * Opinion {@code GLOBAL}/{@code MACRO} (étude "extension-risk-macro-external" §3) : sentiment
 * de marché large (Fear &amp; Greed, via {@code FearGreedStrategy}), sans lien avec un symbole
 * précis. Contrairement à une future opinion {@code EXTERNAL}, cette opinion réutilise le même
 * pipeline Strategy/StrategyAggregator que {@link DefaultMarketOpinion} : le seul indicateur
 * disponible aujourd'hui (Fear &amp; Greed) est une "valeur externe unique" qui ne dépend
 * d'aucune série de prix par-symbole, donc s'intègre sans modification au contrat
 * {@code Strategy#evaluate(MarketContext, StrategyParameters)} existant.
 * <p>
 * {@code GLOBAL} et {@code MACRO} restent fusionnés en un seul scope ({@code GLOBAL}) pour
 * l'instant, faute d'un deuxième indicateur macro-économique réel qui justifierait de les
 * séparer (voir étude, §3.3).
 */
@Component
public class GlobalMarketOpinion extends AbstractMarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(GlobalMarketOpinion.class);

    @Autowired
    private EventBus eventBus;

    @Override
    public OpinionScope getScope() {
        return OpinionScope.GLOBAL;
    }

    @Override
    protected void interpretSignals(
            OpinionContext context,
            MarketOpinionParameters parameters,
            AggregatedStrategySignal aggregatedSignal
    ) {
        for (StrategySignal signal : aggregatedSignal.getSignals()) {
            logger.debug("s:{}, t:{}, c:{}", signal.getScore(), signal.getType(), signal.getConfidence());
        }

        // Opinion GLOBAL : pas de symbole par construction (contexte de marché large), mais on
        // garde la même vérification défensive que DefaultMarketOpinion au cas où un symbole
        // s'y glisserait malgré tout.
        Optional<String> symbol = Optional.empty();
        if (context.marketContext() != null && context.marketContext().symbol() != null) {
            symbol = Optional.of(context.marketContext().symbol());
        }

        Set<String> sources = aggregatedSignal.getSignals().stream()
                .map(StrategySignal::getReason)
                .collect(Collectors.toSet());

        OpinionEvent event = new OpinionEvent(new OpinionSignal(
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
