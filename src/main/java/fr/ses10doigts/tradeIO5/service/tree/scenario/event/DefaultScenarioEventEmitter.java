package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DefaultScenarioEventEmitter implements ScenarioEventEmitter {

    private final EventStore eventStore;

    @Override
    public void emit(ScenarioEvent event) {
        eventStore.append(event);
    }
}