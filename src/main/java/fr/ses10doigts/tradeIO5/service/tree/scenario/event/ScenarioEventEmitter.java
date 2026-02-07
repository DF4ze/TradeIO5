package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;

public interface ScenarioEventEmitter {
    void emit(ScenarioEvent event);
}
