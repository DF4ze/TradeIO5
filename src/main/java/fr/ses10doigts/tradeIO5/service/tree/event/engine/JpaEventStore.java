package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.entity.tree.EventEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.repository.decision.EventRepository;
import fr.ses10doigts.tradeIO5.model.dto.event.PersistableEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(JpaEventStore.class);

    private final EventRepository repository;
    private final ObjectMapper objectMapper;
    private final EventBus bus;


    @PostConstruct
    public void init() {
        bus.subscribe(PersistableEvent.class, this::append);
    }

    @Override
    public void append(PersistableEvent event) {
        try {
            EventEntity entity = new EventEntity();
            entity.setId(event.getId());
            entity.setTargetId(event.getTargetId());
            entity.setType(event.getEventType());
            entity.setTimestamp(event.getTimestamp());
            entity.setPayload(objectMapper.writeValueAsString(event));

            repository.save(entity);

            log.debug("Persisted event {} of type {} at {}", event.getId(), event.getEventType(), event.getTimestamp());
        } catch (Exception e) {
            log.error("Failed to persist event {}: {}", event.getId(), e.getMessage(), e);
        }

    }

    @Override
    public List<PersistableEvent> loadByType(EventType type) {
        return repository.findByType(type).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public PersistableEvent loadById(String id) {
        return repository.findById(id)
                .map(this::toDomain)
                .orElse(null);
    }

    @Override
    public List<PersistableEvent> loadByTargetId(String targetId) {
        return repository.findByTargetId(targetId).stream()
                .map(this::toDomain)
                .toList();
    }

    private PersistableEvent toDomain(EventEntity entity) {
        try {
            return switch (entity.getType()) {
                case SCENARIO -> objectMapper.readValue(entity.getPayload(), ScenarioEvent.class);
                case OPINION -> objectMapper.readValue(entity.getPayload(), OpinionEvent.class);
                default -> throw new IllegalArgumentException("Unknown event type: " + entity.getType());
            };
        } catch (Exception e) {
            log.error("Failed to deserialize event {}: {}", entity.getId(), e.getMessage(), e);
            return null;
        }
    }
}
