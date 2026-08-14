package fr.ses10doigts.tradeIO5.service.tree.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.ActionStepExecutedCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.repository.decision.DecisionSnapshotRepository;
import fr.ses10doigts.tradeIO5.repository.scenario.ScenarioSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultScenarioEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Palier 3, étape 4 (étape 6 du prompt d'implémentation). Moteurs réels construits directement
 * (patron {@code MultiUserIsolationIntegrationTest}), repositories réels via {@code @DataJpaTest}
 * (patron {@code AssetProviderRepositoryTest}).
 */
@DataJpaTest
@DisplayName("DecisionScenarioSnapshotService")
class DecisionScenarioSnapshotServiceTest {

    @Autowired
    private ScenarioSnapshotRepository scenarioSnapshotRepository;

    @Autowired
    private DecisionSnapshotRepository decisionSnapshotRepository;

    private FixedDomainClock clock;
    private DefaultScenarioEngine scenarioEngine;
    private DecisionEngine decisionEngine;
    private DecisionScenarioSnapshotService snapshotService;

    private final ScenarioOwner ownerA = ScenarioOwner.user("userA");
    private final ScenarioOwner ownerB = ScenarioOwner.user("userB");

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(Instant.parse("2026-08-13T10:00:00Z"));
        EventBus eventBus = new EventBus();
        scenarioEngine = new DefaultScenarioEngine(clock, eventBus);
        decisionEngine = new DecisionEngine(clock, eventBus, scenarioEngine);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        snapshotService = new DecisionScenarioSnapshotService(
                scenarioEngine, decisionEngine,
                scenarioSnapshotRepository, decisionSnapshotRepository,
                objectMapper, clock
        );
    }

    private void bringToValidatedAndStable(ScenarioOwner owner, String symbol) {
        ScenarioContext context = new ScenarioContext(owner, Optional.of(symbol), clock, List.of());
        OpinionSignal bullish = new OpinionSignal(
                "OpinionId-" + symbol, Optional.of(symbol), SignalType.BULLISH, SignalType.BULLISH,
                0.95, 0.95, OpinionScope.LOCAL, new HashSet<>(), "reason", clock.now()
        );
        for (int i = 0; i < 4; i++) {
            scenarioEngine.onMarketOpinion(bullish, context);
        }
    }

    @Test
    @DisplayName("takeSnapshot persiste une ligne par scénario/décision actif, tous owners confondus, aucune pour les décisions terminales")
    void takeSnapshot_persistsOnlyActiveEntities() {
        bringToValidatedAndStable(ownerA, "BTC");
        bringToValidatedAndStable(ownerB, "ETH");

        assertEquals(2, scenarioEngine.getAllActiveScenarios(java.time.Duration.ofHours(2), clock.now()).size());
        List<Decision> activeBeforeTransition = decisionEngine.getAllActiveDecisions();
        assertEquals(2, activeBeforeTransition.size(), "Une Decision CREATED doit avoir été créée par owner");

        // La décision de ownerB passe en EXECUTED : ne doit plus apparaître dans la photo.
        Decision decisionB = activeBeforeTransition.stream()
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

        SnapshotResult result = snapshotService.takeSnapshot();

        assertEquals(2, result.scenarioCount());
        assertEquals(1, result.decisionCount());
        assertEquals(2, scenarioSnapshotRepository.count());
        assertEquals(1, decisionSnapshotRepository.count());
    }

    @Test
    @DisplayName("Deux appels successifs sans changement d'état font un upsert (pas de doublon)")
    void takeSnapshot_calledTwice_upsertsWithoutDuplicating() {
        bringToValidatedAndStable(ownerA, "BTC");

        SnapshotResult first = snapshotService.takeSnapshot();
        SnapshotResult second = snapshotService.takeSnapshot();

        assertEquals(first.scenarioCount(), second.scenarioCount());
        assertEquals(first.decisionCount(), second.decisionCount());
        assertEquals(1, scenarioSnapshotRepository.count());
        assertEquals(1, decisionSnapshotRepository.count());
    }
}
