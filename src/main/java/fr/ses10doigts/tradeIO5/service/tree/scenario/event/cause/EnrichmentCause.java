package fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;

public record EnrichmentCause(
        String incomingScenarioId,
        ScenarioState incomingState
) implements ScenarioEventCause {
    public String type() { return "ENRICHMENT"; }
}