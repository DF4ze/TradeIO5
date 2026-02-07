package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.DefaultScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.ScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioEngineIntegrationTest {


    private DefaultScenarioEngine engine;
    private ScenarioEventEmitter emitter;

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

        InMemoryEventStore eventStore = new InMemoryEventStore();
        emitter = new DefaultScenarioEventEmitter(eventStore);
        engine = new DefaultScenarioEngine(
                emitter
        );
    }

    @Test
    void shouldCreateAndObserveScenario() {
        MarketOpinionResult opinion = new MarketOpinionResult(
                "OpinionId",
                SignalType.NEUTRAL,
                SignalType.NEUTRAL,
                1.0,
                1.0,
                new ArrayList<>(),
                ""
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
                emitter
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
        MarketScenario scenario = new DefaultMarketScenario( def, emitter );

        engine.scenarios.put(engine.keyOf(scenario), scenario);

        // L'user courant ne doit pas voir le scénario de l'autre
        List<MarketScenario> visible = engine.getActiveScenarios(owner, Duration.ofDays(1), Instant.now());
        assertTrue(visible.isEmpty(), "Le scénario d'un autre owner ne doit pas être visible");

        engine.cleanup( Duration.ofDays(0), Instant.now()); // durée 0 = expire tout
    }

}
