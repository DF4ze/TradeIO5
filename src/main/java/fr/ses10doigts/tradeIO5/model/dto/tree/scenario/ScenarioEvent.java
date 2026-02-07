package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.ScenarioEventCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public class ScenarioEvent{
    private final String scenarioId;
    private final ScenarioType scenarioType;
    private final ScenarioOwner owner;
    private final Optional<String> symbol;

    private final ScenarioEventType type;
    private final ScenarioEventCause cause;

    private final ScenarioState before;
    private final ScenarioState after;

    private final Instant occurredAt;

    public ScenarioEvent(MarketScenario scenario, ScenarioEventType type, ScenarioEventCause cause, ScenarioState before, Instant now){
        scenarioId = scenario.getId();
        scenarioType = scenario.getType();
        owner = scenario.getOwner();
        symbol = scenario.getSymbol();
        after = scenario.getState();
        occurredAt = now;

        this.type = type;
        this.cause = cause;
        this.before = before;
    }
}
