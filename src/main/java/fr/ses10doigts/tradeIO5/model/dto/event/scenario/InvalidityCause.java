package fr.ses10doigts.tradeIO5.model.dto.event.scenario;

public record InvalidityCause(
        double threshold
) implements ScenarioEventCause {
    public String type() { return "INVALIDITY"; }
}