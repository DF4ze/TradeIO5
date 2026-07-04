package fr.ses10doigts.tradeIO5.model.dto.event.scenario;

public record EngineCause(
        String scenarioId,
        String action,
        String reason
) implements ScenarioEventCause {
}