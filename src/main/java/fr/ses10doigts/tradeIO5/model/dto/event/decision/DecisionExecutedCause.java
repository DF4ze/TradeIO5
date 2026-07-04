package fr.ses10doigts.tradeIO5.model.dto.event.decision;

public record DecisionExecutedCause(
        String decisionId,
        String reason
) implements DecisionEventCause {

}
