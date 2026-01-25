package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionAction;
import lombok.Data;

import java.util.List;

/**
 * Résultat produit par une Decision.
 */
@Data
public class DecisionResult {

    /** Action globale proposée */
    private DecisionAction action;

    /** Niveau de confiance de la décision */
    private double confidence;

    /** Signaux des stratégies ayant contribué */
    private List<StrategySignal> signals;

    /** Informations explicatives (debug, audit, reporting) */
    private String reason;

}
