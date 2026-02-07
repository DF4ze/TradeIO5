package fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause;

public record EngineCause( // TODO refactor
        String scenarioId,
        String action,
        String reason
) implements ScenarioEventCause {
    public String type() { return "ENGINE"; }
}