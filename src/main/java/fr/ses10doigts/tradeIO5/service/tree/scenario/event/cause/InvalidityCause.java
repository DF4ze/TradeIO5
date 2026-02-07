package fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause;

public record InvalidityCause(
        double threshold
) implements ScenarioEventCause {
    public String type() { return "INVALIDITY"; }
}