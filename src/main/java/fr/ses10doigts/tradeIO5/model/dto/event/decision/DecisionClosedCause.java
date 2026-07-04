package fr.ses10doigts.tradeIO5.model.dto.event.decision;

import jakarta.websocket.CloseReason;

public record DecisionClosedCause(
        String decisionId,
        CloseReason reason
) implements DecisionEventCause {

}
