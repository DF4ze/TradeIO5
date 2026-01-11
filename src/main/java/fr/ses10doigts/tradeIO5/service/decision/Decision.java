package fr.ses10doigts.tradeIO5.service.decision;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionResult;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionType;

/**
 * Contrat commun à toutes les décisions.
 * Une Decision orchestre une ou plusieurs Strategies
 * et produit un DecisionResult à partir d'un contexte enrichi.
 */
public interface Decision {

    /**
     * Type de la décision (pour le registry)
     */
    DecisionType getType();

    /**
     * Point d'entrée principal de la décision.
     *
     * @param context    contexte enrichi (market, wallet, user…)
     * @param parameters paramètres génériques de la décision
     * @return résultat de la décision
     */
    DecisionResult decide(DecisionContext context, DecisionParameters parameters);

    /**
     * Retourne le nom de la classe pour le Registry
     * @return
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
