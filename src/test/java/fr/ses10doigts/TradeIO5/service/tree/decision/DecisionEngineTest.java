package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.ActionStepExecutedCause;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionCreatedCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.IntentCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.MarketIntentAction;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Decision - Engine UT")
@ExtendWith(MockitoExtension.class)
class DecisionEngineTest {

    private final ScenarioOwner owner1 = ScenarioOwner.user("user1");
    private final DomainClock clock = new FixedDomainClock(Instant.parse("2026-01-29T20:00:00Z"));

    @Test
    void mapDecisionType_buyReturnsEnter() {
        assertEquals(DecisionType.ENTER, DecisionEngine.mapDecisionType(ExecutionAction.BUY));
    }

    @Test
    void mapDecisionType_sellReturnsExit() {
        assertEquals(DecisionType.EXIT, DecisionEngine.mapDecisionType(ExecutionAction.SELL));
    }

    @Test
    void createDecision_usesCandidateTypeInsteadOfHardcodedExit() {
        EventBus eventBus = new EventBus();
        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        DecisionEngine engine = new DecisionEngine(clock, eventBus, scenarioEngine);

        DecisionCandidate candidate = new DecisionCandidate(
                "BTC/EUR",
                DecisionType.ENTER,
                ExecutionAction.BUY,
                0.95,
                BigDecimal.ONE,
                "Test",
                owner1,
                clock.now(),
                null
        );

        Decision decision = engine.createDecision(candidate);

        // Couvre spécifiquement le bug où createDecision ignorait candidate.type() et écrasait
        // toujours la valeur avec DecisionType.EXIT.
        assertEquals(DecisionType.ENTER, decision.getSnapshot().type());
    }

    @Test
    void onScenarioEvent_buyIntentProducesEnterDecision() {
        EventBus eventBus = new EventBus();

        ActionIntent intent = new ActionIntent(
                MarketIntentAction.BUY,
                "BTC/EUR",
                BigDecimal.ONE,
                0.95,
                "scenario-1",
                "reason",
                clock.now()
        );

        MarketScenario stubScenario = mock(MarketScenario.class);
        when(stubScenario.getSymbol()).thenReturn(Optional.of("BTC/EUR"));
        when(stubScenario.proposeIntent(any())).thenReturn(Optional.of(intent));

        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        when(scenarioEngine.getActiveScenarios(any(), any(), any()))
                .thenReturn(List.of(stubScenario));

        new DecisionEngine(clock, eventBus, scenarioEngine);

        AtomicReference<DecisionEvent> captured = new AtomicReference<>();
        eventBus.subscribe(DecisionEvent.class, captured::set);

        MarketScenario eventScenario = mock(MarketScenario.class);
        when(eventScenario.getId()).thenReturn("scenario-1");
        when(eventScenario.getType()).thenReturn(ScenarioType.TREND_UP);
        when(eventScenario.getOwner()).thenReturn(owner1);
        when(eventScenario.getSymbol()).thenReturn(Optional.of("BTC/EUR"));
        ScenarioState state = new ScenarioState(ScenarioType.TREND_UP, clock.now());
        when(eventScenario.getState()).thenReturn(state);

        ScenarioEvent scenarioEvent = new ScenarioEvent(
                eventScenario,
                ScenarioEventType.ACTION_PROPOSED,
                new IntentCause("scenario-1", intent, "reason"),
                state,
                clock.now()
        );

        eventBus.publish(scenarioEvent);

        assertNotNull(captured.get());
        assertEquals(DecisionType.ENTER, captured.get().getDecisionType());

        // Palier 3, étape 3 : la DecisionCreatedCause doit porter les ActionStep réels de la Decision
        // créée (pas juste "non vide") — couvre le risque d'un mauvais mapping. NB : on vérifie le
        // contenu du (seul) step attendu plutôt que de rejouer engine.createDecision(...)/passer par
        // DecisionEngine.getActiveDecision(...) : Decision.getId() est actuellement construit à partir
        // de champs pas encore assignés à cet instant (bug préexistant, hors scope de ce lot — Decision
        // n'a pas encore de vraie identité stable), donc décisionId ne peut pas servir de clé fiable ici.
        DecisionCreatedCause cause = (DecisionCreatedCause) captured.get().getCause();
        List<ActionStep> actualSteps = cause.actionSteps();

        assertEquals(1, actualSteps.size());
        ActionStep step = actualSteps.getFirst();
        assertNotNull(step.stepId());
        assertEquals(ExecutionAction.BUY, step.executionAction());
        assertEquals(0, BigDecimal.ONE.compareTo(step.quantity()));
        assertNull(step.walletId());
    }

    // ---------- Palier 3, étape 2 : moteur unique partagé (option B3) ----------

    @Test
    @DisplayName("Une seule instance de DecisionEngine produit une DecisionEvent par owner, sans mélange")
    void onScenarioEvent_publishesDecisionEventWithMatchingOwner_forEachOwnerOnSharedInstance() {
        EventBus eventBus = new EventBus();
        ScenarioOwner ownerB = ScenarioOwner.user("user2");

        // Pas de scénarios concurrents mockés : isUnanimousAcrossScopes(...) n'est pas l'objet de
        // ce test (déjà couvert ailleurs), on la neutralise pour se concentrer sur le routage
        // de l'owner porté par chaque ScenarioEvent.
        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        when(scenarioEngine.getActiveScenarios(any(), any(), any())).thenReturn(List.of());

        new DecisionEngine(clock, eventBus, scenarioEngine);

        List<DecisionEvent> captured = new ArrayList<>();
        eventBus.subscribe(DecisionEvent.class, captured::add);

        ActionIntent intentA = new ActionIntent(
                MarketIntentAction.BUY, "BTC/EUR", BigDecimal.ONE, 0.95, "scenario-A", "reason", clock.now());
        ActionIntent intentB = new ActionIntent(
                MarketIntentAction.BUY, "ETH/EUR", BigDecimal.ONE, 0.95, "scenario-B", "reason", clock.now());

        MarketScenario scenarioA = mock(MarketScenario.class);
        when(scenarioA.getId()).thenReturn("scenario-A");
        when(scenarioA.getType()).thenReturn(ScenarioType.TREND_UP);
        when(scenarioA.getOwner()).thenReturn(owner1);
        when(scenarioA.getSymbol()).thenReturn(Optional.of("BTC/EUR"));
        ScenarioState stateA = new ScenarioState(ScenarioType.TREND_UP, clock.now());
        when(scenarioA.getState()).thenReturn(stateA);

        MarketScenario scenarioB = mock(MarketScenario.class);
        when(scenarioB.getId()).thenReturn("scenario-B");
        when(scenarioB.getType()).thenReturn(ScenarioType.TREND_UP);
        when(scenarioB.getOwner()).thenReturn(ownerB);
        when(scenarioB.getSymbol()).thenReturn(Optional.of("ETH/EUR"));
        ScenarioState stateB = new ScenarioState(ScenarioType.TREND_UP, clock.now());
        when(scenarioB.getState()).thenReturn(stateB);

        eventBus.publish(new ScenarioEvent(
                scenarioA, ScenarioEventType.ACTION_PROPOSED,
                new IntentCause("scenario-A", intentA, "reason"), stateA, clock.now()));
        eventBus.publish(new ScenarioEvent(
                scenarioB, ScenarioEventType.ACTION_PROPOSED,
                new IntentCause("scenario-B", intentB, "reason"), stateB, clock.now()));

        assertEquals(2, captured.size());
        assertEquals(owner1, captured.get(0).getOwner());
        assertEquals(ownerB, captured.get(1).getOwner());
    }

    // ---------- Palier 3, étape 4 : getAllActiveDecisions ----------

    @Test
    @DisplayName("getAllActiveDecisions ne renvoie que les décisions CREATED, tous owners confondus")
    void getAllActiveDecisions_returnsOnlyCreatedStatus_acrossAllOwners() {
        EventBus eventBus = new EventBus();
        ScenarioOwner ownerB = ScenarioOwner.user("user2");

        ScenarioEngine scenarioEngine = mock(ScenarioEngine.class);
        when(scenarioEngine.getActiveScenarios(any(), any(), any())).thenReturn(List.of());

        DecisionEngine engine = new DecisionEngine(clock, eventBus, scenarioEngine);

        ActionIntent intentA = new ActionIntent(
                MarketIntentAction.BUY, "BTC/EUR", BigDecimal.ONE, 0.95, "scenario-A", "reason", clock.now());
        ActionIntent intentB = new ActionIntent(
                MarketIntentAction.BUY, "ETH/EUR", BigDecimal.ONE, 0.95, "scenario-B", "reason", clock.now());

        MarketScenario scenarioA = mock(MarketScenario.class);
        when(scenarioA.getId()).thenReturn("scenario-A");
        when(scenarioA.getType()).thenReturn(ScenarioType.TREND_UP);
        when(scenarioA.getOwner()).thenReturn(owner1);
        when(scenarioA.getSymbol()).thenReturn(Optional.of("BTC/EUR"));
        ScenarioState stateA = new ScenarioState(ScenarioType.TREND_UP, clock.now());
        when(scenarioA.getState()).thenReturn(stateA);

        MarketScenario scenarioB = mock(MarketScenario.class);
        when(scenarioB.getId()).thenReturn("scenario-B");
        when(scenarioB.getType()).thenReturn(ScenarioType.TREND_UP);
        when(scenarioB.getOwner()).thenReturn(ownerB);
        when(scenarioB.getSymbol()).thenReturn(Optional.of("ETH/EUR"));
        ScenarioState stateB = new ScenarioState(ScenarioType.TREND_UP, clock.now());
        when(scenarioB.getState()).thenReturn(stateB);

        eventBus.publish(new ScenarioEvent(
                scenarioA, ScenarioEventType.ACTION_PROPOSED,
                new IntentCause("scenario-A", intentA, "reason"), stateA, clock.now()));
        eventBus.publish(new ScenarioEvent(
                scenarioB, ScenarioEventType.ACTION_PROPOSED,
                new IntentCause("scenario-B", intentB, "reason"), stateB, clock.now()));

        List<Decision> beforeTransition = engine.getAllActiveDecisions();
        assertEquals(2, beforeTransition.size(), "Les deux décisions viennent d'être créées (status CREATED)");

        // Fait passer la décision de ownerB en EXECUTED en rejouant son unique ActionStep.
        Decision decisionB = beforeTransition.stream()
                .filter(d -> d.getOwner().equals(ownerB))
                .findFirst()
                .orElseThrow();
        ActionStep stepB = decisionB.getSteps().getFirst();
        decisionB.apply(new DecisionEvent(
                decisionB,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(stepB.stepId(), stepB.executionAction(), stepB.quantity()),
                clock.now()
        ));

        List<Decision> afterTransition = engine.getAllActiveDecisions();
        assertEquals(1, afterTransition.size(), "Seule la décision restée CREATED (ownerA) doit être renvoyée");
        assertEquals(owner1, afterTransition.getFirst().getOwner());
    }
}
