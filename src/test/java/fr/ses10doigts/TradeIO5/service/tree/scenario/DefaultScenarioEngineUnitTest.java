package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.*;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.DefaultScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.EventStore;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.ScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultScenarioEngineUnitTest {

    EventStore eventStore;
    DefaultScenarioEngine engine;

    MarketScenario existingScenario;
    private ScenarioEventEmitter emitter;

    MarketOpinionResult mor;


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

        eventStore = new InMemoryEventStore();
        ScenarioEventEmitter emitter = new DefaultScenarioEventEmitter(eventStore);

        ScenarioDefinition def = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner,
                Optional.of("BTC"),
                clock.now()
        );
        existingScenario = new DefaultMarketScenario( def, emitter );

        engine = new DefaultScenarioEngine(
                emitter
        );

        mor = new MarketOpinionResult(
                "OpinionId",
                SignalType.BEARISH,
                SignalType.BEARISH,
                0.9,
                0.9,
                List.of(),
                "reason"
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
                mock(MarketOpinionResult.class),
                context
        );

        List<ScenarioEvent> events = eventStore.load(existingScenario.getId());

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
        List<ScenarioEvent> events = eventStore.load(scenario.getId());

        assertFalse(events.isEmpty());
        assertTrue(
                events.stream().anyMatch(e ->
                        e.getType() == ScenarioEventType.SCENARIO_CREATED
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
        List<ScenarioEvent> events =
                eventStore.load(existingScenario.getId());

        assertFalse(events.isEmpty());

        assertTrue(
                events.stream().anyMatch(e ->
                        e.getType() == ScenarioEventType.STATE_MUTATED
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
        List<ScenarioEvent> events =
                eventStore.load(existingScenario.getId());

        assertFalse(events.isEmpty());

        assertTrue(
                events.stream().anyMatch(e ->
                        e.getType() == ScenarioEventType.SCENARIO_EXPIRED
                                || e.getType() == ScenarioEventType.SCENARIO_INVALIDATED
                )
        );
    }


}