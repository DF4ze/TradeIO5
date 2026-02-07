package fr.ses10doigts.tradeIO5.service.tree.scenario.event;

import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause.EngineCause;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryEventStoreTest {
    private InMemoryEventStore eventStore;

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
    }

    @Test
    void testAppendAndLoad_singleEvent() {
        String scenarioId = "SC-123";
        ScenarioEvent event = new ScenarioEvent(
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

        List<ScenarioEvent> events = eventStore.load(scenarioId);
        assertEquals(1, events.size(), "Un événement devrait être stocké");
        assertEquals(event, events.get(0), "L'événement stocké doit être identique à celui ajouté");
    }

    @Test
    void testAppendAndLoad_multipleEvents_sameScenario() {
        String scenarioId = "SC-456";
        ScenarioEvent e1 = new ScenarioEvent(
                scenarioId,
                ScenarioType.TREND_UP,
                ScenarioOwner.user("user1"),
                Optional.empty(),
                ScenarioEventType.SCENARIO_CREATED,
                new EngineCause(scenarioId, "EventTest", "Initial test cause"),
                null,
                new ScenarioState(ScenarioType.TREND_UP, Instant.now()),
                Instant.now()
        );
        ScenarioEvent e2 = new ScenarioEvent(
                scenarioId,
                ScenarioType.TREND_UP,
                ScenarioOwner.user("user1"),
                Optional.empty(),
                ScenarioEventType.STATE_MUTATED,
                new EngineCause(scenarioId, "EventTest", "Initial test cause"),
                e1.getAfter(),
                new ScenarioState(ScenarioType.TREND_UP, Instant.now()),
                Instant.now()
        );

        eventStore.append(e1);
        eventStore.append(e2);

        List<ScenarioEvent> events = eventStore.load(scenarioId);
        assertEquals(2, events.size(), "Deux événements devraient être stockés");
        assertSame(e1, events.get(0));
        assertSame(e2, events.get(1));
    }

    @Test
    void testLoad_unknownScenarioId_returnsEmpty() {
        List<ScenarioEvent> events = eventStore.load("unknown-id");
        assertNotNull(events, "La liste ne doit pas être null");
        assertTrue(events.isEmpty(), "La liste doit être vide pour un ID inconnu");
    }


}