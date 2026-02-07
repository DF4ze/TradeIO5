package fr.ses10doigts.tradeIO5.model.dto.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;

import java.util.List;

/**
 * Résultat produit par une Opinion.
 *
 * @param opinionId                String unique définissant l'opinion
 * @param majoritySignal    Action majoritairement proposée
 * @param weightedSignal    Action qui ressort une fois pondérée par les scores
 * @param conviction        Niveau de confiance de la décision
 * @param score             Score brut agrégé (peut être négatif)
 * @param signals           Signaux des stratégies ayant contribué
 * @param reason            Informations explicatives (debug, audit, reporting)
 */
public record MarketOpinionResult(
        String opinionId,
        SignalType majoritySignal,
        SignalType weightedSignal,
        double conviction,
        double score,
        List<StrategySignal> signals,
        String reason
) {

}
