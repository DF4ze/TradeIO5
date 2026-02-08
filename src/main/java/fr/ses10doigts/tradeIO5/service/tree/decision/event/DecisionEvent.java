package fr.ses10doigts.tradeIO5.service.tree.decision.event;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;

import java.time.Instant;

public interface DecisionEvent {

    String decisionId();
    DecisionEventType type();
    Instant occurredAt();

    DecisionStatus resultingStatus();
}