package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Palier 3, étape 2 : documente et prouve le patron de "capture synchrone" que l'orchestrateur
 * (étape 7) réutilisera pour obtenir le résultat d'une Opinion sans passer par un auto-abonnement
 * (retiré à l'étape 1, cf. addendum étude §B du 2026-08-12).
 * <p>
 * Comble aussi un trou de couverture existant : avant ce lot, {@code onOpinionEvent(OpinionEvent,
 * ScenarioOwner)} n'était exercé par aucun test — seul {@code onMarketOpinion} l'était (cf.
 * {@link SharedScenarioEngineMultiOwnerTest}, {@link DefaultScenarioEngineUnitTest}).
 */
@DisplayName("Scenario - OpinionEvent capture bridge (Palier 3, étape 2)")
class OpinionEventCaptureBridgeTest {

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
    @DisplayName("onOpinionEvent(OpinionEvent, ScenarioOwner) crée un scénario pour l'owner passé en paramètre, pas un autre")
    void onOpinionEvent_createsScenarioForPassedOwnerOnly() {
        OpinionSignal signal = new OpinionSignal(
                "OpinionId-direct", Optional.of("ETH"), SignalType.BULLISH, SignalType.BULLISH,
                0.8, 0.8, OpinionScope.LOCAL, Set.of(), "reason", clock.now()
        );
        OpinionEvent event = new OpinionEvent(signal);

        engine.onOpinionEvent(event, ownerA);

        List<MarketScenario> forA = engine.getActiveScenarios(ownerA, Duration.ofDays(1), clock.now());
        List<MarketScenario> forB = engine.getActiveScenarios(ownerB, Duration.ofDays(1), clock.now());

        assertFalse(forA.isEmpty(), "onOpinionEvent doit créer un scénario pour l'owner passé en paramètre");
        assertTrue(forA.stream().allMatch(s -> s.getOwner().equals(ownerA)),
                "Le scénario créé doit appartenir à l'owner passé en paramètre");
        assertTrue(forB.isEmpty(), "onOpinionEvent ne doit créer aucun scénario visible pour un autre owner");
    }

    @Test
    @DisplayName("Capture synchrone via subscribe/unsubscribe : le même OpinionEvent capté une fois peut être propagé à deux owners, scénarios isolés (patron étape 7)")
    void syncCaptureViaSubscribeUnsubscribe_propagatesSameOpinionEventToTwoOwners_scenariosStayIsolated() {
        // Patron documenté sur EventBus.unsubscribe(...) : abonnement temporaire pour capturer
        // synchroniquement un event, puis désabonnement immédiat.
        List<OpinionEvent> captured = new ArrayList<>();
        Consumer<OpinionEvent> capture = captured::add;

        eventBus.subscribe(OpinionEvent.class, capture);
        OpinionSignal signal = new OpinionSignal(
                "OpinionId-bridge", Optional.of("BTC"), SignalType.BULLISH, SignalType.BULLISH,
                0.9, 0.9, OpinionScope.LOCAL, Set.of(), "reason", clock.now()
        );
        eventBus.publish(new OpinionEvent(signal));
        eventBus.unsubscribe(OpinionEvent.class, capture);

        assertEquals(1, captured.size(), "Un seul OpinionEvent doit avoir été capté");
        OpinionEvent capturedEvent = captured.getFirst();

        // Même OpinionEvent capturé une seule fois, propagé explicitement à deux owners distincts
        // — exactement le schéma décrit en étude §E point 6 ("calcul une fois, propagation par
        // owner").
        engine.onOpinionEvent(capturedEvent, ownerA);
        engine.onOpinionEvent(capturedEvent, ownerB);

        List<MarketScenario> forA = engine.getActiveScenarios(ownerA, Duration.ofDays(1), clock.now());
        List<MarketScenario> forB = engine.getActiveScenarios(ownerB, Duration.ofDays(1), clock.now());

        assertFalse(forA.isEmpty(), "Un scénario distinct doit exister pour ownerA");
        assertFalse(forB.isEmpty(), "Un scénario distinct doit exister pour ownerB");
        assertTrue(forA.stream().allMatch(s -> s.getOwner().equals(ownerA)));
        assertTrue(forB.stream().allMatch(s -> s.getOwner().equals(ownerB)));
        assertNotEquals(forA.getFirst().getId(), forB.getFirst().getId(),
                "Chaque owner doit obtenir son propre scénario, pas une référence partagée");
    }
}
