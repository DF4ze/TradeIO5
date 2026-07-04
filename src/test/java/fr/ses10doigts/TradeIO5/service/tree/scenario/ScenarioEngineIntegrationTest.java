package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Scenario - Engine IT")
class ScenarioEngineIntegrationTest {


    private DefaultScenarioEngine engine;
    private EventBus eventBus;

    private ScenarioOwner owner;
    private ScenarioContext context;
    private FixedDomainClock clock;


    @BeforeEach
    void setUp() {

        owner = ScenarioOwner.user("integrationUser");

        clock = new FixedDomainClock(Instant.parse("2024-01-01T00:00:00Z"));
        context = new ScenarioContext(
                owner,
                Optional.of("BTC"),
                clock,
                new ArrayList<>()
        );

        eventBus = new EventBus();
        InMemoryEventStore eventStore = new InMemoryEventStore(eventBus);
        eventStore.init();
        engine = new DefaultScenarioEngine(
                owner,
                clock,
                Set.of("BTC"),
                eventBus
        );
    }

    @Test
    void shouldCreateAndObserveScenario() {
        OpinionSignal opinion = new OpinionSignal(
                "OpinionId",
                Optional.of("BTC"),
                SignalType.NEUTRAL,
                SignalType.NEUTRAL,
                1.0,
                1.0,
                OpinionScope.LOCAL,
                new HashSet<>(),
                "",
                clock.now()
        ); // Crée un objet test simple

        // 1. Appel de l'engine
        engine.onMarketOpinion(opinion, context);

        // 2. Vérifie qu'un scénario a été créé et est actif
        List<MarketScenario> active = engine.getActiveScenarios(
                owner,
                Duration.ofDays(1),
                clock.now()
        );

        assertFalse(active.isEmpty(), "Un scénario doit avoir été créé");
        active.forEach(s -> System.out.println("Scenario actif : " + s.getType() + ", symbol : " + s.getSymbol()));

        engine.cleanup( Duration.ofDays(0), Instant.now()); // durée 0 = expire tout
    }

    @Test
    void shouldCleanupInactiveScenarios() {
        // Ajout manuel d'un scénario inactif
        ScenarioDefinition def = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner,
                Optional.of("BTC"),
                clock.now()
        );
        MarketScenario inactiveScenario = new DefaultMarketScenario(
                def,
                eventBus
        );

        //engine.getActiveScenarios(owner, Instant.now(), Duration.ofDays(1)).clear();
        engine.scenarios.put(engine.keyOf(inactiveScenario), inactiveScenario);

        // Vérifie qu'il est présent avant cleanup
        assertTrue(engine.getActiveScenarios(owner, Duration.ofDays(1), clock.now()).contains(inactiveScenario));

        engine.cleanup( Duration.ofDays(0), Instant.now() ); // durée 0 = expire tout

        // Doit être supprimé
        assertFalse(engine.getActiveScenarios(owner, Duration.ofDays(1), clock.now()).contains(inactiveScenario));
    }

    @Test
    void shouldRespectScenarioOwnerVisibility() {
        ScenarioOwner otherOwner = ScenarioOwner.user("otherUser");
        ScenarioDefinition def = new ScenarioDefinition(
                ScenarioType.RANGE,
                otherOwner,
                Optional.of("ETH"),
                clock.now()
        );
        MarketScenario scenario = new DefaultMarketScenario( def, eventBus );

        engine.scenarios.put(engine.keyOf(scenario), scenario);

        // L'user courant ne doit pas voir le scénario de l'autre
        List<MarketScenario> visible = engine.getActiveScenarios(owner, Duration.ofDays(1), Instant.now());
        assertTrue(visible.isEmpty(), "Le scénario d'un autre owner ne doit pas être visible");

        engine.cleanup( Duration.ofDays(0), Instant.now()); // durée 0 = expire tout
    }

}
