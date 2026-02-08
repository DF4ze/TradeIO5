package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.service.tree.event.PersistableEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class InMemoryEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventStore.class);

    private final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Stockage par eventId */
    private final Map<String, List<PersistableEvent>> store = new ConcurrentHashMap<>();

    private final EventBus bus;

    @PostConstruct
    public void init() {
        bus.subscribe(PersistableEvent.class, this::append);
    }

    @Override
    public void append(PersistableEvent event) {

            store.compute(event.getId(), (id, list) -> {
                if (list == null) list = new ArrayList<>();
                list.add(event);
                return list;
            });

            // Log détaillé
            try {
                log.debug(
                        """
                        [Event][{}] Id={} timestamp={}
                        JsonEvent={}""",
                        event.getEventType(),
                        event.getId(),
                        event.getTimestamp(),
                        MAPPER.writeValueAsString(event)
                );
            } catch (JsonProcessingException e) {
                log.error(
                        "Error serializing (cause or states) [Event] Id={} Type={} timestamp={}\nerror: {}",
                        event.getId(),
                        event.getEventType(),
                        event.getTimestamp(),
                        e.getMessage()
                );
            }

    }

    @Override
    public List<PersistableEvent> loadByType(EventType type) {
        return store.values().stream()
                .flatMap(List::stream)
                .filter(e -> e.getEventType().equals(type))
                .sorted(Comparator.comparing(PersistableEvent::getTimestamp)) // tri croissant
                .collect(Collectors.toList());
    }

    @Override
    public PersistableEvent loadById(String id) {
        return store.getOrDefault(id, List.of())
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<PersistableEvent> loadByTargetId(String targetId) {
        return store.values().stream()
                .flatMap(List::stream)
                .filter(e -> e.getTargetId().equals(targetId))
                .sorted(Comparator.comparing(PersistableEvent::getTimestamp)) // tri croissant
                .collect(Collectors.toList());
    }
}

