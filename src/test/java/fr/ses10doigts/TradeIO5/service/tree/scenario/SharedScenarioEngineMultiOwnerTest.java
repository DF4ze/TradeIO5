package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 3, étape 1 (option B3, moteur unique partagé, owner en paramètre d'appel). Ces tests
 * n'existaient pas avant ce lot car tant qu'une seule instance par owner existait
 * ({@code DefaultScenarioEngineUnitTest}, {@code ScenarioEngineIntegrationTest}), aucun test ne
 * pouvait prouver qu'une même instance lit bien l'owner au niveau de l'appel plutôt que d'un champ
 * d'instance.
 */
@DisplayName("Scenario - Engine partagé multi-owner (Palier 3, étape 1)")
class SharedScenarioEngineMultiOwnerTest {

    private DefaultScenarioEngine engine;
    private EventBus eventBus;
    private FixedDomainClock clock;

    private final ScenarioOwner ownerA = ScenarioOwner.user("userA");
    private final ScenarioOwner ownerB = ScenarioOwner.user("userB");

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(Instant.parse("2026-01-01T00:00:00Z"));
        eventBus = new EventBus();
        engine = new DefaultScenarioEngine(clock, eventBus);
    }

    @Test
    @DisplayName("Une seule instance traite deux owners sans mélanger leurs scénarios")
    void sharedInstance_isolatesScenariosPerOwnerAtCallTime() {
        ScenarioContext contextA = new ScenarioContext(ownerA, Optional.of("BTC"), clock, List.of());
        ScenarioContext contextB = new ScenarioContext(ownerB, Optional.of("ETH"), clock, List.of());

        OpinionSignal opinionForA = new OpinionSignal(
                "OpinionId-A", Optional.of("BTC"), SignalType.NEUTRAL, SignalType.NEUTRAL,
                1.0, 1.0, OpinionScope.LOCAL, Set.of(), "reason", clock.now()
        );
        OpinionSignal opinionForB = new OpinionSignal(
                "OpinionId-B", Optional.of("ETH"), SignalType.NEUTRAL, SignalType.NEUTRAL,
                1.0, 1.0, OpinionScope.LOCAL, Set.of(), "reason", clock.now()
        );

        engine.onMarketOpinion(opinionForA, contextA);
        engine.onMarketOpinion(opinionForB, contextB);

        List<MarketScenario> activeForA = engine.getActiveScenarios(ownerA, Duration.ofDays(1), clock.now());
        List<MarketScenario> activeForB = engine.getActiveScenarios(ownerB, Duration.ofDays(1), clock.now());

        assertFalse(activeForA.isEmpty(), "Un scénario doit avoir été créé pour ownerA");
        assertFalse(activeForB.isEmpty(), "Un scénario doit avoir été créé pour ownerB");

        assertTrue(activeForA.stream().allMatch(s -> s.getOwner().equals(ownerA)),
                "getActiveScenarios(ownerA, ...) ne doit renvoyer que des scénarios de ownerA");
        assertTrue(activeForB.stream().allMatch(s -> s.getOwner().equals(ownerB)),
                "getActiveScenarios(ownerB, ...) ne doit renvoyer que des scénarios de ownerB");
    }

    @Test
    @DisplayName("onMarketOpinion pour ownerB ne doit jamais proposer un intent pour un scénario VALIDATED de ownerA (régression bug ligne 115)")
    void onMarketOpinion_doesNotLeakActionIntentAcrossOwners_afterPriorCallForDifferentOwner() {
        // L'instance a déjà été utilisée pour ownerA juste avant — un bug basé sur un champ
        // d'instance figé (l'ancien this.owner, retiré à l'étape 1) aurait pu laisser une valeur
        // résiduelle influençant l'appel suivant.
        ScenarioContext warmupContextA = new ScenarioContext(ownerA, Optional.of("BTC"), clock, List.of());
        OpinionSignal warmupOpinion = new OpinionSignal(
                "OpinionId-warmup", Optional.of("BTC"), SignalType.NEUTRAL, SignalType.NEUTRAL,
                0.5, 0.1, OpinionScope.LOCAL, Set.of(), "reason", clock.now()
        );
        engine.onMarketOpinion(warmupOpinion, warmupContextA);

        // Scénario VALIDATED+stable, prêt à proposer un intent BUY, déjà présent pour ownerA
        // (manipulation directe de l'état, même patron que DefaultScenarioEngineUnitTest).
        ScenarioDefinition defA = new ScenarioDefinition(
                ScenarioType.TREND_UP, ownerA, Optional.of("BTC"), OpinionScope.LOCAL, clock.now()
        );
        MarketScenario scenarioA = new DefaultMarketScenario(defA, eventBus);
        scenarioA.getState().setStatus(ScenarioStatus.VALIDATED);
        scenarioA.getState().setStable(true);
        scenarioA.getState().setConfidence(1.0);
        scenarioA.getState().setSignal(SignalType.BULLISH);

        ScenarioKey keyA = new ScenarioKey(ownerA, ScenarioType.TREND_UP, Optional.of("BTC"), OpinionScope.LOCAL);
        engine.scenarios.put(keyA, scenarioA);

        List<ScenarioEvent> captured = new ArrayList<>();
        eventBus.subscribe(ScenarioEvent.class, captured::add);

        // Appel pour ownerB, symbole distinct : ne doit jamais déclencher collectActionIntents
        // pour le scénario A, visible seulement de ownerA.
        ScenarioContext contextB = new ScenarioContext(ownerB, Optional.of("ETH"), clock, List.of());
        OpinionSignal opinionForB = new OpinionSignal(
                "OpinionId-B", Optional.of("ETH"), SignalType.NEUTRAL, SignalType.NEUTRAL,
                0.5, 0.1, OpinionScope.LOCAL, Set.of(), "reason", clock.now()
        );
        engine.onMarketOpinion(opinionForB, contextB);

        boolean actionProposedForScenarioA = captured.stream()
                .anyMatch(e -> e.getScenarioEventType() == ScenarioEventType.ACTION_PROPOSED
                        && scenarioA.getId().equals(e.getScenarioId()));

        assertFalse(actionProposedForScenarioA,
                "L'appel pour ownerB ne doit jamais proposer un intent pour le scénario VALIDATED de ownerA");
    }

    @Test
    @DisplayName("Un scénario SystemOwner est visible par tous les owners sous le moteur singleton (§A.4/§E pt2 : vérifié, pas supposé)")
    void systemOwnerScenario_isVisibleForAllOwners_underSharedInstance() {
        ScenarioDefinition defSystem = new ScenarioDefinition(
                ScenarioType.CRASH,
                ScenarioOwner.SYSTEM,
                Optional.empty(),
                OpinionScope.LOCAL,
                clock.now()
        );
        MarketScenario systemScenario = new DefaultMarketScenario(defSystem, eventBus);

        ScenarioKey systemKey = new ScenarioKey(
                ScenarioOwner.SYSTEM, ScenarioType.CRASH, Optional.empty(), OpinionScope.LOCAL
        );
        engine.scenarios.put(systemKey, systemScenario);

        List<MarketScenario> visibleForA = engine.getActiveScenarios(ownerA, Duration.ofDays(1), clock.now());
        List<MarketScenario> visibleForB = engine.getActiveScenarios(ownerB, Duration.ofDays(1), clock.now());

        assertTrue(visibleForA.contains(systemScenario),
                "Un scénario SystemOwner doit être visible pour ownerA sous le moteur partagé");
        assertTrue(visibleForB.contains(systemScenario),
                "Un scénario SystemOwner doit être visible pour ownerB sous le moteur partagé");
    }
}
