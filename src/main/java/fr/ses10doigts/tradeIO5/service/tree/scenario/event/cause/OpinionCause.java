package fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;

public record OpinionCause(
        String opinionId,
        SignalType signal,
        double conviction
) implements ScenarioEventCause {
    public String type() { return "OPINION"; }
}