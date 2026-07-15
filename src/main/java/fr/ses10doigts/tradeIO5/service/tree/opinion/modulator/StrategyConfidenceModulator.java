package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import lombok.Getter;

/**
 * Adaptateur {@link ConfidenceModulator} pour {@link MarketOpinionHelper#computeConfidenceModulationFactor}
 * (étude "unification-confidence-modulator", 2026-07-16, §2), seul consommateur :
 * {@code AbstractMarketOpinion} (Strategies {@code StrategyType.CONFIDENCE_MODULATOR}, ex.
 * {@code MovementQualificationStrategy}, {@code OrderFlowStrategy}). La fonction enrobée n'est pas
 * modifiée (même signature, même comportement).
 * <p>
 * Contrairement à {@link SentimentShiftModulator}/{@link StalenessModulator}, cet adaptateur a
 * réellement besoin de {@code context} : évaluer la {@code Strategy} sous-jacente requiert
 * {@code context.marketContext()} (bougies, symbole...), résolu seulement au moment de
 * {@link #evaluate}, pas à la construction.
 * <p>
 * {@link ModulationResult} (étude §2) ne porte volontairement que {@code (applied, factor, reason)}
 * — pas le {@link StrategySignal} complet. Or {@code AbstractMarketOpinion} a besoin de ce signal
 * complet pour la traçabilité existante ({@code AggregatedStrategySignal.signals} inclut déjà les
 * modulateurs, cf. javadoc historique de {@code AbstractMarketOpinion#applyConfidenceModulators}).
 * Plutôt que d'élargir le contrat {@link ModulationResult} (qui doit rester valable pour les deux
 * autres implémentations, qui n'ont pas de {@code StrategySignal}), cet adaptateur mémorise le
 * dernier {@link StrategySignal} évalué et l'expose via {@link #getLastSignal()} — {@code Strategy}
 * n'est ainsi évaluée qu'une seule fois par appel à {@link #evaluate}, jamais deux.
 */
public class StrategyConfidenceModulator implements ConfidenceModulator {

    /**
     * -- GETTER --
     * La
     *  enrobée, ex. pour construire un message de log fidèle au comportement historique.
     */
    @Getter
    private final Strategy strategy;
    private final StrategyParameters parameters;

    @Getter
    private StrategySignal lastSignal;

    public StrategyConfidenceModulator(Strategy strategy, StrategyParameters parameters) {
        this.strategy = strategy;
        this.parameters = parameters;
    }

    @Override
    public ModulationResult evaluate(OpinionContext context, MarketOpinionParameters opinionParameters) {
        lastSignal = strategy.evaluate(context.marketContext(), parameters);

        if (!lastSignal.isValid()) {
            return new ModulationResult(false, 1.0, lastSignal.getReason());
        }

        double factor = MarketOpinionHelper.computeConfidenceModulationFactor(lastSignal.getScore());
        return new ModulationResult(true, factor, "score=" + lastSignal.getScore());
    }

}
