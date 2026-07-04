package fr.ses10doigts.tradeIO5.model.dto.event;

import fr.ses10doigts.tradeIO5.model.dto.event.scenario.ScenarioEventCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;

@Getter
@RequiredArgsConstructor
public class ScenarioEvent implements PersistableEvent {
    private final String id;
    private final String scenarioId;
    private final EventType eventType = EventType.SCENARIO;
    private final ScenarioType scenarioType;
    private final ScenarioOwner owner;
    private final Optional<String> symbol;

    private final ScenarioEventType scenarioEventType;
    private final ScenarioEventCause cause;

    private final ScenarioState before;
    private final ScenarioState after;

    private final Instant timestamp;

    public ScenarioEvent(MarketScenario scenario, ScenarioEventType scenarioEventType, ScenarioEventCause cause, ScenarioState before, Instant now){
        id = "[ScenarioEvent]"+scenario.getId();
        scenarioId = scenario.getId();
        scenarioType = scenario.getType();
        owner = scenario.getOwner();
        symbol = scenario.getSymbol();
        after = scenario.getState();
        timestamp = now;

        this.scenarioEventType = scenarioEventType;
        this.cause = cause;
        this.before = before;
    }

    @Override
    public String getTargetId() {
        return scenarioId;
    }
}
