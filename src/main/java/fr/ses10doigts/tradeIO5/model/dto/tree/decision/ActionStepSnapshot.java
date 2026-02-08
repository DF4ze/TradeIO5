package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;

public record ActionStepSnapshot(
        String stepId,
        ExecutionAction actionType,
        double quantity
) {}
