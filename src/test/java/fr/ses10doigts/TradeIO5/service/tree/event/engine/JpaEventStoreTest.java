package fr.ses10doigts.tradeIO5.service.tree.event.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.PersistableEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionCreatedCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.repository.decision.EventRepository;
import fr.ses10doigts.tradeIO5.service.tree.decision.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Palier 3, étape 4 (point 1a du prompt d'implémentation) : régression du bug où
 * {@code JpaEventStore.toDomain()} n'avait pas de cas pour {@code EventType.DECISION} (le
 * {@code default} levait {@code IllegalArgumentException}, silencieusement avalée par le
 * {@code catch} englobant et journalisée comme une erreur de désérialisation plutôt qu'un type
 * manquant). Utilise {@code @DataJpaTest} pour un {@link EventRepository} réel adossé à H2 (même
 * patron que {@code AssetProviderRepositoryTest}), {@link JpaEventStore} instancié directement
 * (pas un bean Spring dans ce slice de test).
 */
@DataJpaTest
@DisplayName("JpaEventStore")
class JpaEventStoreTest {

    @Autowired
    private EventRepository eventRepository;

    private JpaEventStore jpaEventStore;

    private final ScenarioOwner owner = ScenarioOwner.user("user1");
    private final Instant createdAt = Instant.parse("2026-08-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        // Même mécanisme d'enregistrement de modules que l'ObjectMapper auto-configuré par Spring
        // Boot (JavaTimeModule pour Instant, Jdk8Module pour Optional) : findAndRegisterModules()
        // scanne le classpath, pas de config manuelle à dupliquer.
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EventBus eventBus = new EventBus();
        jpaEventStore = new JpaEventStore(eventRepository, objectMapper, eventBus);
    }

    private DecisionEvent buildDecisionEvent() {
        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                "BTC/EUR",
                owner,
                DecisionType.ENTER,
                createdAt
        );
        ActionStep step = new ActionStep("step1", ExecutionAction.BUY, BigDecimal.ONE, null);
        Decision decision = new Decision(snapshot, List.of(step));

        return new DecisionEvent(
                decision,
                DecisionEventType.DECISION_CREATED,
                new DecisionCreatedCause(decision.getId(), "test", decision.getSteps()),
                createdAt
        );
    }

    @Test
    @DisplayName("append(...) puis loadByTargetId(...) renvoie le DecisionEvent désérialisé avec les bons champs")
    void appendThenLoadByTargetId_roundTripsDecisionEvent() {
        DecisionEvent event = buildDecisionEvent();

        jpaEventStore.append(event);

        List<PersistableEvent> reloaded = jpaEventStore.loadByTargetId(event.getDecisionId());

        assertEquals(1, reloaded.size());
        PersistableEvent reloadedEvent = reloaded.getFirst();
        assertInstanceOf(DecisionEvent.class, reloadedEvent);

        DecisionEvent decisionEvent = (DecisionEvent) reloadedEvent;
        assertEquals(event.getDecisionId(), decisionEvent.getDecisionId());
        assertEquals(event.getSymbol(), decisionEvent.getSymbol());
        assertEquals(event.getDecisionType(), decisionEvent.getDecisionType());
        assertEquals(event.getDecisionEventType(), decisionEvent.getDecisionEventType());
        assertEquals(event.getTimestamp(), decisionEvent.getTimestamp());
    }

    @Test
    @DisplayName("append(...) puis loadById(...) renvoie le même DecisionEvent, aucune exception levée")
    void appendThenLoadById_roundTripsDecisionEvent() {
        DecisionEvent event = buildDecisionEvent();

        jpaEventStore.append(event);

        PersistableEvent reloaded = jpaEventStore.loadById(event.getId());

        assertNotNull(reloaded, "loadById ne doit pas renvoyer null pour un DecisionEvent persisté");
        assertInstanceOf(DecisionEvent.class, reloaded);
        assertEquals(event.getId(), reloaded.getId());
    }
}
