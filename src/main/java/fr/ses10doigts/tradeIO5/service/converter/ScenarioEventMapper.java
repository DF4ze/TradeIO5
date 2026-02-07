package fr.ses10doigts.tradeIO5.service.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioEventEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.ScenarioEventCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;

import java.util.Optional;

public class ScenarioEventMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ScenarioEventEntity toEntity(ScenarioEvent event) {
        ScenarioEventEntity e = new ScenarioEventEntity();
        e.setScenarioId(event.getScenarioId());
        e.setScenarioType(event.getScenarioType().name());
        e.setOwner(event.getOwner().toString());
        e.setSymbol(event.getSymbol().orElse(null));
        e.setEventType(event.getType().name());
        e.setOccurredAt(event.getOccurredAt());

        try {
            e.setCauseJson(MAPPER.writeValueAsString(event.getCause()));
            e.setBeforeJson(MAPPER.writeValueAsString(event.getBefore()));
            e.setAfterJson(MAPPER.writeValueAsString(event.getAfter()));
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to serialize scenario event", ex);
        }

        return e;
    }

    public static ScenarioEvent toDomain(ScenarioEventEntity e) {
        try {
            ScenarioEventCause cause = MAPPER.readValue(e.getCauseJson(), ScenarioEventCause.class);
            ScenarioState before = MAPPER.readValue(e.getBeforeJson(), ScenarioState.class);
            ScenarioState after = MAPPER.readValue(e.getAfterJson(), ScenarioState.class);

            return new ScenarioEvent(
                    e.getScenarioId(),
                    ScenarioType.valueOf(e.getScenarioType()),
                    ScenarioOwner.fromString(e.getOwner()),
                    Optional.ofNullable(e.getSymbol()),
                    ScenarioEventType.valueOf(e.getEventType()),
                    cause,
                    before,
                    after,
                    e.getOccurredAt()
            );
        } catch (JsonProcessingException ex) {
            throw new RuntimeException("Failed to deserialize scenario event", ex);
        }
    }
}
