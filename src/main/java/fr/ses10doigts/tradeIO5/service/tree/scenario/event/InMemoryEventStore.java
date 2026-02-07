package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryEventStore implements EventStore {

    private final Logger log = LoggerFactory.getLogger(InMemoryEventStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Stockage par scenarioId */
    private final Map<String, List<ScenarioEvent>> store = new ConcurrentHashMap<>();

    @Override
    public void append(ScenarioEvent event) {
        store.compute(event.getScenarioId(), (id, list) -> {
            if (list == null) list = new ArrayList<>();
            list.add(event);
            return list;
        });
        // Log détaillé
        try {
            log.debug(
                    """
                            [Event][{}] ScenarioId={} Owner={} Symbol={} OccurredAt={}
                                   Cause={}\s
                                   Before={}\s
                                   After={}""",
                    event.getType(),
                    event.getScenarioId(),
                    event.getOwner(),
                    event.getSymbol().orElse("GLOBAL"),
                    event.getOccurredAt(),
                    MAPPER.writeValueAsString(event.getCause()),
                    MAPPER.writeValueAsString(event.getBefore()),
                    MAPPER.writeValueAsString(event.getAfter())
            );

        } catch (JsonProcessingException e) {
            log.error("Error serializing (cause or states) [Event] ScenarioId={} Type={} Owner={} Symbol={} OccurredAt={}\nerror: {}",
                    event.getScenarioId(),
                    event.getType(),
                    event.getOwner(),
                    event.getSymbol().orElse("GLOBAL"),
                    event.getOccurredAt(),
                    e.getMessage()
            );

        }
    }

    @Override
    public List<ScenarioEvent> load(String scenarioId) {
        return store.getOrDefault(scenarioId, List.of());
    }
}