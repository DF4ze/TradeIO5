package fr.ses10doigts.tradeIO5.service.tree.opinion.impl;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.opinion.MarketOpinion;
import fr.ses10doigts.tradeIO5.service.tree.opinion.advisor.DecisionAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Opinion {@code EXTERNAL} (étude "extension-risk-macro-external" §4) : avis d'un
 * {@link DecisionAdvisor} (aujourd'hui {@code OpenAIAdvisor}) à comparer, pas à fusionner,
 * avec les opinions techniques LOCAL/GLOBAL — l'arbitrage entre scopes pour une même
 * symbole se fait désormais dans {@code DecisionEngine} (règle d'unanimité), pas ici.
 * <p>
 * Implémente {@link MarketOpinion} directement plutôt que d'étendre
 * {@code AbstractMarketOpinion} : cette dernière est bâtie entièrement autour de
 * {@code StrategyAggregator} (agréger plusieurs {@code StrategySignal}), un mécanisme qui n'a
 * pas de sens ici — il n'y a qu'un seul appel direct au {@code DecisionAdvisor}, rien à agréger.
 */
@Component
public class ExternalMarketOpinion implements MarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(ExternalMarketOpinion.class);

    private final DecisionAdvisor advisor;

    @Autowired
    private EventBus eventBus;

    public ExternalMarketOpinion(DecisionAdvisor advisor) {
        this.advisor = advisor;
    }

    @Override
    public OpinionScope getScope() {
        return OpinionScope.EXTERNAL;
    }

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters) {
        // Pas de Strategy à évaluer ici : le DecisionAdvisor consomme le contexte déjà résolu
        // (OpinionContext, y compris les indicateurs déjà calculés par ailleurs), pas de
        // bougies à pré-charger pour son propre compte.
        return Map.of();
    }

    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        LlmAdvice advice = advisor.advise(context);

        if (!advice.isValid()) {
            // Fallback déjà géré par AbstractAdvisor (timeout/erreur -> LlmAdvice.invalid()) :
            // on ne publie pas d'OpinionEvent plutôt que de publier un signal NEUTRAL qui
            // pourrait être classifié à tort en scénario RANGE par ScenarioFactory.
            logger.warn("{} : LlmAdvice invalide (timeout/erreur), aucun OpinionEvent publié", getName());
            return;
        }

        Optional<String> symbol = Optional.empty();
        if (context.marketContext() != null && context.marketContext().symbol() != null) {
            symbol = Optional.of(context.marketContext().symbol());
        }

        SignalType signal = MarketOpinionHelper.mapIntentActionToSignalType(advice.getAction());
        double directionalSign = switch (signal) {
            case BULLISH -> 1.0;
            case BEARISH -> -1.0;
            case NEUTRAL -> 0.0;
        };
        double score = directionalSign * advice.getConfidence();

        OpinionEvent event = new OpinionEvent(new OpinionSignal(
                getId(),
                symbol,
                signal,
                signal,
                advice.getConfidence(),
                score,
                getScope(),
                Set.of(advisor.getType().toString()),
                advice.getRationale(),
                context.clock().now()
        ));

        eventBus.publish(event);
    }
}
