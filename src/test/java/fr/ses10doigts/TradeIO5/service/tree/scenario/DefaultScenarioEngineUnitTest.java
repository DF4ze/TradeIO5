package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.model.dto.event.PersistableEvent;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventStore;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
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
                OpinionScope.LOCAL,
                clock.now()
        );
        existingScenario = new DefaultMarketScenario( def, eventBus );

        engine = new DefaultScenarioEngine(
                clock,
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
                Optional.of("BTC"),
                OpinionScope.LOCAL
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

        MarketScenario scenario = active.getFirst();
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

        // Palier 3, étape 3 : le ScenarioEvent doit porter le scope du scénario d'origine (ici
        // OpinionScope.LOCAL, porté par `mor` dans setUp()).
        ScenarioEvent createdEvent = events.stream()
                .filter(e -> e instanceof ScenarioEvent)
                .map(e -> (ScenarioEvent) e)
                .filter(e -> e.getScenarioEventType() == ScenarioEventType.SCENARIO_CREATED)
                .findFirst()
                .orElseThrow();
        assertEquals(OpinionScope.LOCAL, createdEvent.getScope());
    }

    @Test
    void shouldMergeScenarioIfSameKeyExists() {

        // GIVEN

        ScenarioKey key = new ScenarioKey(
                owner,
                ScenarioType.TREND_UP,
                Optional.of("BTC"),
                OpinionScope.LOCAL
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
                Optional.empty(),
                OpinionScope.LOCAL
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
                Optional.of("BTC"),
                OpinionScope.LOCAL
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

    // ---------- Palier 3, étape 3 : ScenarioEvent porte le scope ----------

    @Test
    @DisplayName("ScenarioEvent publié depuis un scénario EXTERNAL porte bien OpinionScope.EXTERNAL (pas figé sur LOCAL)")
    void scenarioEvent_carriesExternalScope() {
        ScenarioDefinition externalDef = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner,
                Optional.of("BTC"),
                OpinionScope.EXTERNAL,
                clock.now()
        );
        MarketScenario externalScenario = new DefaultMarketScenario(externalDef, eventBus);

        ScenarioKey key = new ScenarioKey(owner, ScenarioType.TREND_UP, Optional.of("BTC"), OpinionScope.EXTERNAL);
        engine.scenarios.put(key, externalScenario);

        // cleanup(...) est la façon la plus simple de déclencher isolément la publication d'un
        // ScenarioEvent (même patron que shouldCleanupInactiveScenarioAndEmitEvent) : le scénario
        // vient d'être créé sur l'horloge fixée à 2024-01-01, donc largement expiré face à
        // Instant.now() réel.
        engine.cleanup(Duration.ofDays(1), Instant.now());

        List<PersistableEvent> events = eventStore.loadByTargetId(externalScenario.getId());
        assertFalse(events.isEmpty());

        ScenarioEvent scopedEvent = events.stream()
                .filter(e -> e instanceof ScenarioEvent)
                .map(e -> (ScenarioEvent) e)
                .findFirst()
                .orElseThrow();

        assertEquals(OpinionScope.EXTERNAL, scopedEvent.getScope());
    }

    // ---------- Dédup des ActionIntent (étape 2, palier 1) ----------

    @Test
    void collectActionIntents_proposesOnceThenSuppressesRepeatWithinSameEpisode() {
        ScenarioKey key = new ScenarioKey(owner, ScenarioType.TREND_UP, Optional.of("BTC"), OpinionScope.LOCAL);
        engine.scenarios.put(key, existingScenario);

        bringToValidatedAndStable(existingScenario);

        List<ActionIntent> first = engine.collectActionIntents(owner, clock.now());
        List<ActionIntent> second = engine.collectActionIntents(owner, clock.now());

        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
    }

    @Test
    void collectActionIntents_newEpisodeAfterLeavingValidatedAllowsNewIntent() {
        ScenarioKey key = new ScenarioKey(owner, ScenarioType.TREND_UP, Optional.of("BTC"), OpinionScope.LOCAL);
        engine.scenarios.put(key, existingScenario);

        bringToValidatedAndStable(existingScenario);
        List<ActionIntent> firstEpisode = engine.collectActionIntents(owner, clock.now());
        assertEquals(1, firstEpisode.size());

        // Sortie de VALIDATED/stable (même patron de manipulation directe de l'état que
        // DefaultMarketScenarioTest.testInvalidation) : reset de la mémoire de dédup.
        existingScenario.getState().setStatus(ScenarioStatus.CONFIRMING);
        existingScenario.getState().setStable(false);
        List<ActionIntent> whileNotValidated = engine.collectActionIntents(owner, clock.now());
        assertTrue(whileNotValidated.isEmpty());

        // Revalidation : nouvel épisode, un nouvel intent doit pouvoir être proposé.
        existingScenario.getState().setStatus(ScenarioStatus.VALIDATED);
        existingScenario.getState().setStable(true);
        List<ActionIntent> secondEpisode = engine.collectActionIntents(owner, clock.now());
        assertEquals(1, secondEpisode.size());
    }

    @Test
    void cleanup_purgesProposedScenarioIdMemory() {
        ScenarioKey key = new ScenarioKey(owner, ScenarioType.TREND_UP, Optional.of("BTC"), OpinionScope.LOCAL);
        engine.scenarios.put(key, existingScenario);

        bringToValidatedAndStable(existingScenario);
        List<ActionIntent> first = engine.collectActionIntents(owner, clock.now());
        assertEquals(1, first.size());

        // Le scénario devient inactif (INVALIDATED) puis est retiré par cleanup(...).
        existingScenario.getState().setStatus(ScenarioStatus.INVALIDATED);
        engine.cleanup(Duration.ofDays(1), clock.now());
        assertTrue(engine.scenarios.isEmpty());

        // Réinsertion d'un scénario avec le même id : si l'id n'avait pas été purgé de la
        // mémoire de dédup au cleanup, aucun intent ne serait reproposé malgré ce nouvel épisode.
        engine.scenarios.put(key, existingScenario);
        existingScenario.getState().setStatus(ScenarioStatus.VALIDATED);
        existingScenario.getState().setStable(true);
        existingScenario.getState().setConfidence(1.0);

        List<ActionIntent> afterCleanupAndReinsertion = engine.collectActionIntents(owner, clock.now());
        assertEquals(1, afterCleanupAndReinsertion.size());
    }

    private void bringToValidatedAndStable(MarketScenario scenario) {
        OpinionSignal bullish = new OpinionSignal(
                "OpinionId-bullish",
                Optional.of("BTC"),
                SignalType.BULLISH,
                SignalType.BULLISH,
                0.95,
                0.95,
                OpinionScope.LOCAL,
                Set.of(),
                "reason",
                clock.now()
        );
        for (int i = 0; i < 4; i++) {
            scenario.observe(bullish, context);
        }
    }

}