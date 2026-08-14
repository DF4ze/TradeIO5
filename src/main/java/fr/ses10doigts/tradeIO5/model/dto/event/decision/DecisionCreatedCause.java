package fr.ses10doigts.tradeIO5.model.dto.event.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;

import java.util.List;

public record DecisionCreatedCause(
        String decisionId,
        String reason,
        List<ActionStep> actionSteps
) implements DecisionEventCause {

}
