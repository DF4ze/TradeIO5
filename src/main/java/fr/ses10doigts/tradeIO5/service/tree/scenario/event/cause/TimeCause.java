package fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause;

import java.time.Duration;
import java.time.Instant;

public record TimeCause(
        Instant lastUpdate,
        Instant now,
        Duration delay
) implements ScenarioEventCause {
    public String type() { return "TIME"; }
}