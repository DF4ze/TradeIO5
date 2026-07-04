package fr.ses10doigts.tradeIO5.model.dto.event.decision;

public record ActionStepFailedCause(
        String stepId,
        String reason
) implements DecisionEventCause {


}
