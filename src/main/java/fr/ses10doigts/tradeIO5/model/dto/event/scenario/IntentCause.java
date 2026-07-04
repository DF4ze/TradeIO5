package fr.ses10doigts.tradeIO5.model.dto.event.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;

public record IntentCause(
        String scenarioId,
        ActionIntent intent,
        String reason
) implements ScenarioEventCause {
}
