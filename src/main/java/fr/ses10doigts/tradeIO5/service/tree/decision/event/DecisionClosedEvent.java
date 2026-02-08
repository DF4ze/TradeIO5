package fr.ses10doigts.tradeIO5.service.tree.decision.event;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import jakarta.websocket.CloseReason;

import java.time.Instant;

public record DecisionClosedEvent(
        String decisionId,
        CloseReason reason,
        Instant occurredAt
) implements DecisionEvent {

    @Override
    public DecisionEventType type() {
        return DecisionEventType.DECISION_CLOSED;
    }

    @Override
    public DecisionStatus resultingStatus() {
        return DecisionStatus.CLOSED;
    }
}
