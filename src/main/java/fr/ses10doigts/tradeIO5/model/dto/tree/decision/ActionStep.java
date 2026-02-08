package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;


public record ActionStep(
        String stepId,
        ExecutionAction executionAction,
        double quantity) {

}
