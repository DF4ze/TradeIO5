package fr.ses10doigts.tradeIO5.model.dto.event.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;

public record OpinionCause(
        String opinionId,
        SignalType signal,
        double conviction
) implements ScenarioEventCause {
    public String type() { return "OPINION"; }
}