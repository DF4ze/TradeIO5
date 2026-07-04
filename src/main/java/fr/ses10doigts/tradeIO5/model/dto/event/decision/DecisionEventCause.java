package fr.ses10doigts.tradeIO5.model.dto.event.decision;

public sealed interface DecisionEventCause
        permits ActionStepExecutedCause,
                ActionStepFailedCause,
                DecisionCreatedCause,
                DecisionExecutedCause,
                DecisionClosedCause
{}
