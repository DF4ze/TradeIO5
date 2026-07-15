package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.StrategyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.AggregatedStrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyAggregatorParam;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.ConfidenceModulation;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.ModulationResult;
import fr.ses10doigts.tradeIO5.service.tree.opinion.modulator.StrategyConfidenceModulator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Classe de base pour les décisions concrètes.
 * Fournit l'orchestration standard des stratégies.
 */
public abstract class AbstractMarketOpinion implements MarketOpinion {
    private final Logger logger = LoggerFactory.getLogger(AbstractMarketOpinion.class);

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(MarketOpinionParameters parameters) {
        return parameters.getStrategies().stream()
                .map(key -> key.getStrategy().getRequiredCandles(key.getParameters()))
                .flatMap(map -> map.entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        Math::max
                ));
    }

    /**
     * Méthode template :
     * - les {@code StrategyKey} de type {@code ENTRY}/{@code EXIT} sont déléguées au
     *   {@link StrategyAggregator}, seul responsable de l'agrégation additive (score,
     *   weightedSignal final, détection de conflit)
     * - les {@code StrategyKey} de type {@link StrategyType#CONFIDENCE_MODULATOR} (ex :
     *   {@code MovementQualificationStrategy}, {@code OrderFlowStrategy}) n'entrent jamais dans
     *   cette somme : elles sont évaluées séparément et leur score converti en un facteur
     *   ({@link MarketOpinionHelper#computeConfidenceModulationFactor}) qui n'atténue que la
     *   confidence finale, jamais le score directionnel — et une modulatrice invalide/en erreur
     *   ne fait jamais échouer les Strategies ENTRY (pas de contagion all-or-nothing entre les
     *   deux familles, contrairement à ce qui se passerait si elles partageaient la même liste
     *   {@code StrategyAggregatorParam})
     * - l'interprétation du résultat agrégé (déjà modulé) est laissée à l'implémentation
     */
    @Override
    public void decide(OpinionContext context, MarketOpinionParameters parameters) {
        List<StrategyKey> keys = parameters.getStrategies();

        List<StrategyAggregatorParam> entryParams = keys.stream()
                .filter(key -> !isConfidenceModulator(key.getStrategy()))
                .map(key -> new StrategyAggregatorParam(key.getStrategy(), key.getParameters()))
                .toList();

        List<StrategyAggregatorParam> modulatorParams = keys.stream()
                .filter(key -> isConfidenceModulator(key.getStrategy()))
                .map(key -> new StrategyAggregatorParam(key.getStrategy(), key.getParameters()))
                .toList();

        AggregatedStrategySignal aggregatedSignal = StrategyAggregator.evaluate(context.marketContext(), entryParams);

        if (!modulatorParams.isEmpty()) {
            aggregatedSignal = applyConfidenceModulators(context, parameters, modulatorParams, aggregatedSignal);
        }

        interpretSignals(context, parameters, aggregatedSignal);
    }

    private static boolean isConfidenceModulator(Strategy strategy) {
        return strategy.getType().contains(StrategyType.CONFIDENCE_MODULATOR);
    }

    /**
     * Évalue les Strategies modulatrices séparément de {@link StrategyAggregator} et applique
     * leur facteur combiné (produit des facteurs individuels, chacun dans {@code ]0,1]}) à la
     * confidence de {@code base} — jamais à son score. Une modulatrice invalide (donnée
     * manquante/en erreur) est simplement ignorée (loggée, pas d'atténuation), plutôt que
     * d'invalider toute l'Opinion comme le ferait {@code StrategyAggregator.aggregate} si elle
     * était mélangée aux Strategies ENTRY.
     * <p>
     * Étude "unification-confidence-modulator" : {@code computeConfidenceModulationFactor} n'est
     * plus appelée directement ici, mais via l'adaptateur {@link StrategyConfidenceModulator} + la
     * boucle commune {@link ConfidenceModulation} (même calcul, même résultat). Chaque Strategy
     * n'est évaluée qu'une seule fois par adaptateur ({@link StrategyConfidenceModulator#evaluate}),
     * le {@code StrategySignal} brut restant accessible via
     * {@link StrategyConfidenceModulator#getLastSignal()} pour la traçabilité ci-dessous.
     */
    private AggregatedStrategySignal applyConfidenceModulators(
            OpinionContext context, MarketOpinionParameters parameters,
            List<StrategyAggregatorParam> modulatorParams, AggregatedStrategySignal base) {

        List<StrategyConfidenceModulator> modulators = modulatorParams.stream()
                .map(param -> new StrategyConfidenceModulator(param.getStrategy(), param.getParameters()))
                .toList();
        List<ModulationResult> results = ConfidenceModulation.evaluateAll(modulators, context, parameters);

        double factor = 1.0;
        List<StrategySignal> modulatorSignals = new ArrayList<>();

        for (int i = 0; i < modulators.size(); i++) {
            StrategyConfidenceModulator modulator = modulators.get(i);
            ModulationResult result = results.get(i);
            modulatorSignals.add(modulator.getLastSignal());

            if (result.applied()) {
                factor *= result.factor();
            } else {
                logger.warn("Confidence modulator {} invalide ({}), ignorée (aucune atténuation appliquée)",
                        modulator.getStrategy().getName(), result.reason());
            }
        }

        List<StrategySignal> allSignals = new ArrayList<>(base.getSignals());
        allSignals.addAll(modulatorSignals);

        String explanation = base.getExplanation();
        if (factor < 1.0) {
            explanation = explanation + String.format(" | confidence atténuée par modulateur(s) de fiabilité (facteur=%.2f)", factor);
        }

        return AggregatedStrategySignal.builder()
                .finalSignal(base.getFinalSignal())
                .score(base.getScore())
                .confidence(base.getConfidence() * factor)
                .conflictDetected(base.isConflictDetected())
                .signals(allSignals)
                .explanation(explanation)
                .build();
    }

    //
    // TOOLS
    //
    /**
     * Chaque décision définit sa propre logique métier ici.
     */
    abstract protected void interpretSignals(
            OpinionContext context,
            MarketOpinionParameters parameters,
            AggregatedStrategySignal aggregatedSignal);


}
