package fr.ses10doigts.tradeIO5.model.dto.event.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;

import java.math.BigDecimal;

public record ActionStepExecutedCause(
        String stepId,
        ExecutionAction action,
        BigDecimal quantity
        //ExecutionResult executionResult,
) implements DecisionEventCause {


}
