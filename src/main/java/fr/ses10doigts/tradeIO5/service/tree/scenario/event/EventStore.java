package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;

import java.util.List;

public interface EventStore {

    void append(ScenarioEvent event);
    List<ScenarioEvent> load(String scenarioId);

}
