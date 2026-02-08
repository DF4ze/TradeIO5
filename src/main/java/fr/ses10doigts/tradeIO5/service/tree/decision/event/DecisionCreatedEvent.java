package fr.ses10doigts.tradeIO5.service.tree.decision.event;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;

import java.time.Instant;

public record DecisionCreatedEvent(
        String decisionId,
        Instant occurredAt,
        DecisionSnapshot snapshot
) implements DecisionEvent {

    @Override
    public DecisionEventType type() {
        return DecisionEventType.DECISION_CREATED;
    }

    @Override
    public DecisionStatus resultingStatus() {
        return DecisionStatus.CREATED;
    }
}
