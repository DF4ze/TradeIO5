package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.model.dto.event.PersistableEvent;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.EngineCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventStore - In Memory")
class InMemoryEventStoreTest {
    private InMemoryEventStore eventStore;


    @BeforeEach
    void setUp() {
        EventBus bus = new EventBus();
        eventStore = new InMemoryEventStore(bus);
        eventStore.init();
    }

    @Test
    void testAppendAndLoad_singleEvent() {
        String scenarioId = "SC-123";
        ScenarioEvent event = new ScenarioEvent(
                "EventId",
                scenarioId,
                ScenarioType.TREND_UP,
                ScenarioOwner.user("user1"),
                Optional.of("BTCUSD"),
                ScenarioEventType.SCENARIO_CREATED,
                new EngineCause(scenarioId, "EventTest", "Initial test cause"),
                null,
                new ScenarioState(ScenarioType.TREND_UP, Instant.now()),
                Instant.now()
        );

        eventStore.append(event);

        List<PersistableEvent> events = eventStore.loadByTargetId(scenarioId);
        assertEquals(1, events.size(), "Un événement devrait être stocké");
        assertEquals(event, events.get(0), "L'événement stocké doit être identique à celui ajouté");
    }

    @Test
    void testAppendAndLoad_multipleEvents_sameScenario() {
        Instant now = Instant.now();
        String scenarioId = "SC-456";
        ScenarioEvent e1 = new ScenarioEvent(
                "EventId1",
                scenarioId,
                ScenarioType.TREND_UP,
                ScenarioOwner.user("user1"),
                Optional.empty(),
                ScenarioEventType.SCENARIO_CREATED,
                new EngineCause(scenarioId, "EventTest", "Initial test cause"),
                null,
                new ScenarioState(ScenarioType.TREND_UP, Instant.now()),
                now
        );
        ScenarioEvent e2 = new ScenarioEvent(
                "EventId2",
                scenarioId,
                ScenarioType.TREND_UP,
                ScenarioOwner.user("user1"),
                Optional.empty(),
                ScenarioEventType.STATE_MUTATED,
                new EngineCause(scenarioId, "EventTest", "Initial test cause"),
                e1.getAfter(),
                new ScenarioState(ScenarioType.TREND_UP, Instant.now()),
                now.plusSeconds(1)
        );

        eventStore.append(e1);
        eventStore.append(e2);

        List<PersistableEvent> events = eventStore.loadByTargetId(scenarioId);
        assertEquals(2, events.size(), "Deux événements devraient être stockés");
        assertEquals(e1.getId(), events.get(0).getId());
        assertEquals(e2.getId(), events.get(1).getId());
    }

    @Test
    void testLoad_unknownScenarioId_returnsEmpty() {
        List<PersistableEvent> events = eventStore.loadByTargetId("unknown-id");
        assertNotNull(events, "La liste ne doit pas être null");
        assertTrue(events.isEmpty(), "La liste doit être vide pour un ID inconnu");
    }


}