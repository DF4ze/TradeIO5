package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioEventEntity;
import fr.ses10doigts.tradeIO5.repository.decision.ScenarioEventRepository;
import fr.ses10doigts.tradeIO5.service.converter.ScenarioEventMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaEventStore implements EventStore {

    private final Logger log = LoggerFactory.getLogger(JpaEventStore.class);
    private final ScenarioEventRepository repository;

    @Override
    public void append(ScenarioEvent event) {
        ScenarioEventEntity entity = ScenarioEventMapper.toEntity(event);
        repository.save(entity);
        log.debug("Persisted event {} for scenario {}", event.getType(), event.getScenarioId());
    }

    @Override
    public List<ScenarioEvent> load(String scenarioId) {
        return repository.findByScenarioId(scenarioId).stream()
                .map(ScenarioEventMapper::toDomain)
                .toList();
    }
}