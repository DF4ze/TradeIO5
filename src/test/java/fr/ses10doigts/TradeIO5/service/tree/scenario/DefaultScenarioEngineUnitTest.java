package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.PersistableEvent;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventStore;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("Scenario - Engine UT")
@ExtendWith(MockitoExtension.class)
class DefaultScenarioEngineUnitTest {

    EventStore eventStore;
    DefaultScenarioEngine engine;

    MarketScenario existingScenario;
    EventBus eventBus;

    OpinionSignal mor;


    private final ScenarioOwner owner = ScenarioOwner.user("user1");
    private final ScenarioOwner otherOwner = ScenarioOwner.user("user2");

    private ScenarioContext context;
    private FixedDomainClock clock;

    @BeforeEach
    void setUp() {

        clock = new FixedDomainClock(Instant.parse("2024-01-01T00:00:00Z"));
        context = new ScenarioContext(
                owner,
                Optional.of("BTC"),
                clock,
                List.of()
        );

        eventBus = new EventBus();
        eventStore = new InMemoryEventStore(eventBus);
        eventStore.init();


        ScenarioDefinition def = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner,
                Optional.of("BTC"),
                clock.now()
        );
        existingScenario = new DefaultMarketScenario( def, eventBus );

        engine = new DefaultScenarioEngine(
                owner,
                clock,
                Set.of("BTC"),
                eventBus
        );

        mor = new OpinionSignal(
                "OpinionId",
                Optional.of("BTC"),
                SignalType.BEARISH,
                SignalType.BEARISH,
                0.9,
                0.9,
                OpinionScope.LOCAL,
                Set.of(),
                "reason",
                clock.now()
        );
    }

    @Test
    void shouldObserveExistingScenarioAndEmitEventWhenStateChanged() {

        ScenarioKey key = new ScenarioKey(
                owner,
                ScenarioType.TREND_UP,
                Optional.of("BTC")
        );
        engine.scenarios.put(key, existingScenario);

        engine.onMarketOpinion(
                mock(OpinionSignal.class),
                context
        );

        List<PersistableEvent> events = eventStore.loadByTargetId(existingScenario.getId());

        assertThat(events).isNotEmpty();
    }

    @Test
    void shouldCreateNewScenarioFromFactory() {

        // when
        engine.onMarketOpinion(
                mor,
                context
        );

        // then
        List<MarketScenario> active = engine.getActiveScenarios(
                owner,
                Duration.ofDays(1),
                clock.now()
        );

        assertEquals(1, active.size());

        MarketScenario scenario = active.get(0);
        assertEquals(owner, scenario.getOwner());

        // 🔥 vérification événementielle
        List<PersistableEvent> events = eventStore.loadByTargetId(scenario.getId());

        assertFalse(events.isEmpty());
        assertTrue(
                events.stream()
                        .filter(e -> e instanceof ScenarioEvent)
                        .map(e -> (ScenarioEvent) e).anyMatch(e ->
                        e.getScenarioEventType() == ScenarioEventType.SCENARIO_CREATED
                )
        );
    }

    @Test
    void shouldMergeScenarioIfSameKeyExists() {

        // GIVEN

        ScenarioKey key = new ScenarioKey(
                owner,
                ScenarioType.TREND_UP,
                Optional.of("BTC")
        );

        engine.scenarios.put(key, existingScenario);


        // WHEN
        engine.onMarketOpinion(mor, context);
        engine.onMarketOpinion(mor, context);

        // THEN — événements produits
        List<PersistableEvent> events =
                eventStore.loadByTargetId(existingScenario.getId());

        assertFalse(events.isEmpty());

        assertTrue(
                events.stream()
                        .filter(e -> e instanceof ScenarioEvent)
                        .map(e -> (ScenarioEvent) e)
                        .anyMatch(e ->
                        e.getScenarioEventType() == ScenarioEventType.STATE_MUTATED
                )
        );
    }


    @Test
    void shouldNotExposeOtherUserScenario() {
        ScenarioKey key = new ScenarioKey(
                otherOwner,
                ScenarioType.CRASH,
                Optional.empty()
        );

        engine.scenarios.put(key, existingScenario);

        List<MarketScenario> visible = engine.getActiveScenarios(
                owner,
                Duration.ofDays(1),
                clock.now()
        );

        assertTrue(visible.isEmpty());
    }

    @Test
    void shouldCleanupInactiveScenarioAndEmitEvent() {

        // GIVEN

        ScenarioKey key = new ScenarioKey(
                owner,
                ScenarioType.TREND_UP,
                Optional.of("BTC")
        );
        engine.scenarios.put(key, existingScenario);


        // WHEN
        engine.cleanup(
                Duration.ofDays(1),
                Instant.now()
        );

        // THEN — scénario supprimé
        assertTrue(engine.scenarios.isEmpty());

        // THEN — événement émis
        List<PersistableEvent> events =
                eventStore.loadByTargetId(existingScenario.getId());

        assertFalse(events.isEmpty());

        assertTrue(
                events.stream()
                        .filter(e -> e instanceof ScenarioEvent)
                        .map(e -> (ScenarioEvent) e)
                        .anyMatch(e ->
                        e.getScenarioEventType() == ScenarioEventType.SCENARIO_EXPIRED
                                || e.getScenarioEventType() == ScenarioEventType.SCENARIO_INVALIDATED
                )
        );
    }


}