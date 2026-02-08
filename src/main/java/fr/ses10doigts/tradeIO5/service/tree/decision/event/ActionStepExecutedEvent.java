package fr.ses10doigts.tradeIO5.service.tree.decision.event;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;

import java.time.Instant;

public record ActionStepExecutedEvent(
        String decisionId,
        String actionStepId,
        //ExecutionResult executionResult,
        Instant occurredAt
) implements DecisionEvent {

    @Override
    public DecisionEventType type() {
        return DecisionEventType.ACTION_STEP_EXECUTED;
    }

    @Override
    public DecisionStatus resultingStatus() {
        return DecisionStatus.EXECUTED;
    }
}
