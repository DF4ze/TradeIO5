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
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.repository.decision.DecisionSnapshotRepository;
import fr.ses10doigts.tradeIO5.repository.decision.EventRepository;
import fr.ses10doigts.tradeIO5.repository.scenario.ScenarioSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.JpaEventStore;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultScenarioEngine;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 3, étape 4 (étape 8 du prompt d'implémentation) : test d'intégration bout-en-bout de la
 * restauration au (re)démarrage. Repositories réels via {@code @DataJpaTest} (patron {@code
 * AssetProviderRepositoryTest}), moteurs réels construits directement (patron {@code
 * MultiUserIsolationIntegrationTest}). Le "redémarrage" est simulé en construisant de NOUVELLES
 * instances de moteur (vides) et un nouveau {@link DecisionScenarioRestoreService} pointant sur les
 * mêmes repositories H2 — exactement ce qu'un vrai redémarrage ferait (nouveau contexte Spring, même
 * base).
 * <p>
 * Palier 3, étape 6 : la logique de restauration a été extraite de {@code
 * DecisionScenarioRestoreRunner} vers {@link DecisionScenarioRestoreService} — seule
 * l'instanciation/l'appel ci-dessous ({@link #newRestoreService}) a changé, les assertions des tests
 * historiques ({@code scenario_mutatedAfterSnapshot_...} etc.) restent inchangées. Deux tests dédiés
 * au filtre owner (introduit par cette même étape) ont été ajoutés en bas de fichier.
 */
@DataJpaTest
@DisplayName("Décision/Scénario - restauration au redémarrage")
class DecisionScenarioRestoreIntegrationTest {

    @Autowired
    private ScenarioSnapshotRepository scenarioSnapshotRepository;

    @Autowired
    private DecisionSnapshotRepository decisionSnapshotRepository;

    @Autowired
    private EventRepository eventRepository;

    private FixedDomainClock clock;
    private EventBus eventBus;
    private ObjectMapper objectMapper;
    private DefaultScenarioEngine scenarioEngine;
    private DecisionEngine decisionEngine;
    private DecisionScenarioSnapshotService snapshotService;

    private static final Instant T0 = Instant.parse("2026-08-13T08:00:00Z");

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(T0);
        eventBus = new EventBus();
        objectMapper = new ObjectMapper().findAndRegisterModules();

        // JpaEventStore réel, abonné au même eventBus : persiste réellement chaque
        // ScenarioEvent/DecisionEvent publié pendant le test, exactement comme en production.
        JpaEventStore jpaEventStore = new JpaEventStore(eventRepository, objectMapper, eventBus);
        jpaEventStore.init();

        scenarioEngine = new DefaultScenarioEngine(clock, eventBus);
        decisionEngine = new DecisionEngine(clock, eventBus, scenarioEngine);
        snapshotService = new DecisionScenarioSnapshotService(
                scenarioEngine, decisionEngine,
                scenarioSnapshotRepository, decisionSnapshotRepository,
                objectMapper, clock
        );
    }

    private DecisionScenarioRestoreService newRestoreService(DefaultScenarioEngine targetScenarioEngine, DecisionEngine targetDecisionEngine) {
        return new DecisionScenarioRestoreService(
                scenarioSnapshotRepository, decisionSnapshotRepository, eventRepository,
                targetScenarioEngine, targetDecisionEngine,
                objectMapper, new EventBus() // bus neutre : la restauration n'a pas besoin de republier
        );
    }

    private OpinionSignal bullish(String symbol) {
        return new OpinionSignal(
                "OpinionId-" + symbol, Optional.of(symbol), SignalType.BULLISH, SignalType.BULLISH,
                0.95, 0.95, OpinionScope.LOCAL, new HashSet<>(), "reason", clock.now()
        );
    }

    @Test
    @DisplayName("Scénario muté après la photo : l'état restauré reflète les événements post-photo, pas la photo elle-même")
    void scenario_mutatedAfterSnapshot_restoresPostSnapshotState() {
        ScenarioOwner owner = ScenarioOwner.user("userScenario");
        ScenarioContext context = new ScenarioContext(owner, Optional.of("BTC"), clock, List.of());

        // 2 opinions : scénario partiellement mûri (pas encore VALIDATED).
        scenarioEngine.onMarketOpinion(bullish("BTC"), context);
        scenarioEngine.onMarketOpinion(bullish("BTC"), context);

        MarketScenario live = scenarioEngine.getActiveScenarios(owner, Duration.ofHours(2), clock.now()).getFirst();
        String scenarioId = live.getId();
        ScenarioStatus statusAtPhoto = live.getState().getStatus();

        clock.advance(Duration.ofMinutes(30));
        snapshotService.takeSnapshot();

        clock.advance(Duration.ofMinutes(30));
        // Mutations post-photo : pousse le scénario au-delà de son état photographié.
        scenarioEngine.onMarketOpinion(bullish("BTC"), context);
        scenarioEngine.onMarketOpinion(bullish("BTC"), context);

        ScenarioStatus statusAfterMutation = scenarioEngine.getActiveScenarios(owner, Duration.ofHours(2), clock.now())
                .stream().filter(s -> s.getId().equals(scenarioId)).findFirst().orElseThrow()
                .getState().getStatus();
        assertTrue(statusAfterMutation.ordinal() > statusAtPhoto.ordinal(),
                "Sanity check : le scénario doit avoir progressé après la photo (sinon le test ne prouve rien)");

        // "Redémarrage" : nouveau moteur vide + restauration.
        DefaultScenarioEngine restoredEngine = new DefaultScenarioEngine(clock, new EventBus());
        newRestoreService(restoredEngine, new DecisionEngine(clock, new EventBus(), restoredEngine)).restoreAll();

        MarketScenario restored = restoredEngine.getActiveScenarios(owner, Duration.ofHours(2), clock.now())
                .stream().filter(s -> s.getId().equals(scenarioId)).findFirst().orElseThrow();

        assertEquals(statusAfterMutation, restored.getState().getStatus(),
                "L'état restauré doit refléter les événements post-photo, pas l'état capturé par la photo");
    }

    @Test
    @DisplayName("Decision exécutée après la photo : le statut restauré est EXECUTED, pas CREATED comme au moment de la photo")
    void decision_executedAfterSnapshot_restoresExecutedStatus() {
        ScenarioOwner owner = ScenarioOwner.user("userDecision");
        ScenarioContext context = new ScenarioContext(owner, Optional.of("BTC"), clock, List.of());

        for (int i = 0; i < 4; i++) {
            scenarioEngine.onMarketOpinion(bullish("BTC"), context);
        }

        List<Decision> activeDecisions = decisionEngine.getAllActiveDecisions();
        assertEquals(1, activeDecisions.size(), "Une Decision CREATED doit avoir été produite par la validation du scénario");
        Decision liveDecision = activeDecisions.getFirst();
        String decisionId = liveDecision.getId();
        ActionStep step = liveDecision.getSteps().getFirst();

        clock.advance(Duration.ofMinutes(30));
        snapshotService.takeSnapshot(); // photo : status CREATED

        clock.advance(Duration.ofMinutes(30));
        // Exécution post-photo : publiée sur le bus (persistée par JpaEventStore), comme le ferait
        // un futur executor (Palier 3, étape 7, hors scope de ce lot).
        eventBus.publish(new DecisionEvent(
                liveDecision,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(step.stepId(), step.executionAction(), step.quantity()),
                clock.now()
        ));

        // "Redémarrage"
        DefaultScenarioEngine restoredScenarioEngine = new DefaultScenarioEngine(clock, new EventBus());
        DecisionEngine restoredDecisionEngine = new DecisionEngine(clock, new EventBus(), restoredScenarioEngine);
        newRestoreService(restoredScenarioEngine, restoredDecisionEngine).restoreAll();

        Decision restored = restoredDecisionEngine.getActiveDecision(
                        activeDecisions.getFirst().getSnapshot().decisionId())
                .orElseThrow(() -> new AssertionError("Decision restaurée introuvable pour la clé snapshot.decisionId()"));

        assertEquals(decisionId, restored.getId(), "L'id restauré doit rester Decision.getId(), pas régénéré");
        assertEquals(DecisionStatus.EXECUTED, restored.getStatus(),
                "Le statut restauré doit refléter l'exécution post-photo, pas CREATED comme au moment de la photo");
    }

    @Test
    @DisplayName("Création après la photo : un scénario ET une décision jamais snapshottés sont bien restaurés")
    void scenarioAndDecision_createdAfterLastSnapshot_arePresentAfterRestore() {
        // Une première photo, pour établir une référence non vide (autre owner, sans rapport).
        ScenarioOwner photographedOwner = ScenarioOwner.user("userPhotographed");
        ScenarioContext photographedContext = new ScenarioContext(photographedOwner, Optional.of("ETH"), clock, List.of());
        scenarioEngine.onMarketOpinion(bullish("ETH"), photographedContext);
        clock.advance(Duration.ofMinutes(30));
        snapshotService.takeSnapshot();

        // Scénario + décision créés entièrement APRÈS cette photo, jamais snapshottés.
        clock.advance(Duration.ofMinutes(30));
        ScenarioOwner newOwner = ScenarioOwner.user("userNeverPhotographed");
        ScenarioContext newContext = new ScenarioContext(newOwner, Optional.of("SOL"), clock, List.of());
        for (int i = 0; i < 4; i++) {
            // Horloge légèrement avancée à chaque appel (comme en usage réel, les opinions
            // n'arrivent jamais à la nanoseconde près) : donne un signal temporel exploitable pour
            // départager le "vrai" scénario conservé par le moteur des objets fantômes créés puis
            // aussitôt jetés par DefaultScenarioEngine.onMarketOpinion à chaque appel où la même
            // ScenarioKey existe déjà (cf. javadoc DecisionScenarioRestoreService#restoreScenarios).
            clock.advance(Duration.ofSeconds(1));
            scenarioEngine.onMarketOpinion(bullish("SOL"), newContext);
        }

        MarketScenario newScenario = scenarioEngine.getActiveScenarios(newOwner, Duration.ofHours(2), clock.now()).getFirst();
        Decision newDecision = decisionEngine.getAllActiveDecisions().stream()
                .filter(d -> d.getOwner().equals(newOwner))
                .findFirst()
                .orElseThrow();

        assertEquals(0, scenarioSnapshotRepository.findById(newScenario.getId()).map(e -> 1).orElse(0),
                "Sanity check : ce scénario ne doit jamais avoir été photographié");

        // "Redémarrage"
        DefaultScenarioEngine restoredScenarioEngine = new DefaultScenarioEngine(clock, new EventBus());
        DecisionEngine restoredDecisionEngine = new DecisionEngine(clock, new EventBus(), restoredScenarioEngine);
        newRestoreService(restoredScenarioEngine, restoredDecisionEngine).restoreAll();

        boolean scenarioRestored = restoredScenarioEngine.getActiveScenarios(newOwner, Duration.ofHours(2), clock.now())
                .stream().anyMatch(s -> s.getId().equals(newScenario.getId()));
        assertTrue(scenarioRestored, "Le scénario jamais photographié doit être présent après restauration");

        boolean decisionRestored = restoredDecisionEngine.getAllActiveDecisions().stream()
                .anyMatch(d -> d.getId().equals(newDecision.getId()) && d.getOwner().equals(newOwner));
        assertTrue(decisionRestored, "La décision jamais photographiée doit être présente après restauration");
    }

    @Test
    @DisplayName("Cas vide : aucune photo, aucun événement — la restauration ne lève pas d'exception, moteurs vides")
    void emptyState_restoresWithoutException_enginesStayEmpty() {
        DefaultScenarioEngine restoredScenarioEngine = new DefaultScenarioEngine(clock, new EventBus());
        DecisionEngine restoredDecisionEngine = new DecisionEngine(clock, new EventBus(), restoredScenarioEngine);

        assertDoesNotThrow(() -> newRestoreService(restoredScenarioEngine, restoredDecisionEngine).restoreAll());

        assertTrue(restoredScenarioEngine.getAllActiveScenarios(Duration.ofHours(2), clock.now()).isEmpty());
        assertTrue(restoredDecisionEngine.getAllActiveDecisions().isEmpty());
    }

    // ---------- Palier 3, étape 6 : restauration owner-scopée ----------

    @Test
    @DisplayName("restoreOwner(ownerA) ne restaure que les données de A (scénario+décision snapshottés puis mutés), rien de B")
    void restoreOwner_onlyRestoresFilteredOwnersData_bothSnapshottedAndMutated() {
        ScenarioOwner ownerA = ScenarioOwner.user("ownerA-filter");
        ScenarioOwner ownerB = ScenarioOwner.user("ownerB-filter");

        ScenarioContext contextA = new ScenarioContext(ownerA, Optional.of("BTC"), clock, List.of());
        ScenarioContext contextB = new ScenarioContext(ownerB, Optional.of("ETH"), clock, List.of());

        for (int i = 0; i < 4; i++) {
            scenarioEngine.onMarketOpinion(bullish("BTC"), contextA);
        }
        for (int i = 0; i < 4; i++) {
            scenarioEngine.onMarketOpinion(bullish("ETH"), contextB);
        }

        clock.advance(Duration.ofMinutes(30));
        snapshotService.takeSnapshot(); // photo des deux owners

        // Mutation post-photo pour chacun (exécution de leur décision respective).
        clock.advance(Duration.ofMinutes(30));
        Decision decisionA = decisionEngine.getAllActiveDecisions().stream()
                .filter(d -> d.getOwner().equals(ownerA)).findFirst().orElseThrow();
        Decision decisionB = decisionEngine.getAllActiveDecisions().stream()
                .filter(d -> d.getOwner().equals(ownerB)).findFirst().orElseThrow();
        ActionStep stepA = decisionA.getSteps().getFirst();
        ActionStep stepB = decisionB.getSteps().getFirst();
        eventBus.publish(new DecisionEvent(decisionA, DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(stepA.stepId(), stepA.executionAction(), stepA.quantity()), clock.now()));
        eventBus.publish(new DecisionEvent(decisionB, DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(stepB.stepId(), stepB.executionAction(), stepB.quantity()), clock.now()));

        DefaultScenarioEngine restoredScenarioEngine = new DefaultScenarioEngine(clock, new EventBus());
        DecisionEngine restoredDecisionEngine = new DecisionEngine(clock, new EventBus(), restoredScenarioEngine);
        RestoreSummary summary = newRestoreService(restoredScenarioEngine, restoredDecisionEngine).restoreOwner(ownerA);

        assertEquals(1, summary.scenarioCount());
        assertEquals(1, summary.decisionCount());

        assertFalse(restoredScenarioEngine.getActiveScenarios(ownerA, Duration.ofHours(2), clock.now()).isEmpty(),
                "Le scénario de ownerA doit être restauré");
        assertTrue(restoredScenarioEngine.getActiveScenarios(ownerB, Duration.ofHours(2), clock.now()).isEmpty(),
                "restoreOwner(ownerA) ne doit rien restaurer pour ownerB");

        assertTrue(restoredDecisionEngine.getAllActiveDecisions().stream().anyMatch(d -> d.getOwner().equals(ownerA)),
                "La décision de ownerA doit être restaurée");
        assertTrue(restoredDecisionEngine.getAllActiveDecisions().stream().noneMatch(d -> d.getOwner().equals(ownerB)),
                "restoreOwner(ownerA) ne doit restaurer aucune décision de ownerB");

        Decision restoredA = restoredDecisionEngine.getActiveDecision(decisionA.getSnapshot().decisionId()).orElseThrow();
        assertEquals(DecisionStatus.EXECUTED, restoredA.getStatus(),
                "La mutation post-photo de ownerA (exécution) doit être reflétée après restauration owner-scopée");
    }

    @Test
    @DisplayName("restoreOwner(ownerA) ignore une création entièrement nouvelle de ownerB survenue après la dernière photo de A")
    void restoreOwner_ignoresOtherOwnersEntirelyNewCreation_afterReferenceSnapshot() {
        ScenarioOwner ownerA = ScenarioOwner.user("ownerA-newcreation");
        ScenarioOwner ownerB = ScenarioOwner.user("ownerB-newcreation");

        // Photo de référence, ownerA seul.
        ScenarioContext contextA = new ScenarioContext(ownerA, Optional.of("BTC"), clock, List.of());
        scenarioEngine.onMarketOpinion(bullish("BTC"), contextA);
        clock.advance(Duration.ofMinutes(30));
        snapshotService.takeSnapshot();

        // Création entièrement nouvelle pour ownerB, jamais snapshottée, survenue après la photo.
        clock.advance(Duration.ofMinutes(30));
        ScenarioContext contextB = new ScenarioContext(ownerB, Optional.of("ETH"), clock, List.of());
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(1));
            scenarioEngine.onMarketOpinion(bullish("ETH"), contextB);
        }

        MarketScenario newScenarioB = scenarioEngine.getActiveScenarios(ownerB, Duration.ofHours(2), clock.now()).getFirst();
        Decision newDecisionB = decisionEngine.getAllActiveDecisions().stream()
                .filter(d -> d.getOwner().equals(ownerB)).findFirst().orElseThrow();

        DefaultScenarioEngine restoredScenarioEngine = new DefaultScenarioEngine(clock, new EventBus());
        DecisionEngine restoredDecisionEngine = new DecisionEngine(clock, new EventBus(), restoredScenarioEngine);
        newRestoreService(restoredScenarioEngine, restoredDecisionEngine).restoreOwner(ownerA);

        boolean scenarioBLeaked = restoredScenarioEngine.getActiveScenarios(ownerB, Duration.ofHours(2), clock.now())
                .stream().anyMatch(s -> s.getId().equals(newScenarioB.getId()));
        assertFalse(scenarioBLeaked,
                "restoreOwner(ownerA) ne doit pas faire apparaître une création entièrement nouvelle de ownerB (filtre appliqué aux événements, pas seulement aux photos)");

        boolean decisionBLeaked = restoredDecisionEngine.getAllActiveDecisions().stream()
                .anyMatch(d -> d.getId().equals(newDecisionB.getId()));
        assertFalse(decisionBLeaked,
                "restoreOwner(ownerA) ne doit pas faire apparaître une décision entièrement nouvelle de ownerB");
    }
}
