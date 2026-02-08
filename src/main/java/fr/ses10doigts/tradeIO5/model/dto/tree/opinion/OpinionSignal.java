package fr.ses10doigts.tradeIO5.model.dto.tree.opinion;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Résultat produit par une Opinion.
 *
 * @param opinionId         String unique définissant l'opinion
 * @param weightedSignal    Action qui ressort une fois pondérée par les scores
 * @param confidence        Niveau de confiance de la décision
 * @param sources           Signaux des stratégies ayant contribué
 * @param reason            Informations explicatives (debug, audit, reporting)
 */
public record OpinionSignal(
     String opinionId,
     Optional<String> symbol,
     SignalType majoritySignal,
     SignalType weightedSignal,
     double confidence,
     double score,
     OpinionScope scope,
     Set<String> sources,
     String reason,
     Instant timestamp
) {

}
