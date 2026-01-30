package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ScenarioEngineIntegrationTest {

    @Autowired
    private ScenarioEngineImpl engine;

    private ScenarioOwner owner;
    private ScenarioContext context;
    private ExecutionMode executionMode;
    private FixedDomainClock clock;

    @BeforeEach
    void setUp() {
        executionMode = ExecutionMode.BACKTEST;
        owner = ScenarioOwner.user("integrationUser");

        clock = new FixedDomainClock(Instant.parse("2024-01-01T00:00:00Z"));
        context = new ScenarioContext(
                owner,
                Optional.of("BTC"),
                clock,
                clock.now(),
                new ArrayList<>()
        );
    }

    @Test
    void shouldCreateAndObserveScenario() {
        MarketOpinionResult opinion = new MarketOpinionResult(
                SignalType.NEUTRAL,
                SignalType.NEUTRAL,
                1.0,
                1.0,
                new ArrayList<>(),
                ""
        ); // Crée un objet test simple

        // 1. Appel de l'engine
        engine.onMarketOpinion(opinion, context, executionMode);

        // 2. Vérifie qu'un scénario a été créé et est actif
        List<MarketScenario> active = engine.getActiveScenarios(
                owner,
                clock.now(),
                Duration.ofDays(1)
        );

        assertFalse(active.isEmpty(), "Un scénario doit avoir été créé");
        active.forEach(s -> System.out.println("Scenario actif : " + s.getType() + ", symbol : " + s.getSymbol()));

        engine.cleanup(Instant.now(), Duration.ofDays(0), executionMode); // durée 0 = expire tout
    }

    @Test
    void shouldCleanupInactiveScenarios() {
        // Ajout manuel d'un scénario inactif
        MarketScenario inactiveScenario = new MarketScenarioImpl(
                ScenarioType.TREND_UP,
                owner,
                "BTC",
                clock.now(),
                clock
        );

        //engine.getActiveScenarios(owner, Instant.now(), Duration.ofDays(1)).clear();
        engine.scenarios.put(engine.keyOf(inactiveScenario), inactiveScenario);

        // Vérifie qu'il est présent avant cleanup
        assertTrue(engine.getActiveScenarios(owner, clock.now(), Duration.ofDays(1)).contains(inactiveScenario));

        engine.cleanup(Instant.now(), Duration.ofDays(0), executionMode); // durée 0 = expire tout

        // Doit être supprimé
        assertFalse(engine.getActiveScenarios(owner, clock.now(), Duration.ofDays(1)).contains(inactiveScenario));
    }

    @Test
    void shouldRespectScenarioOwnerVisibility() {
        ScenarioOwner otherOwner = ScenarioOwner.user("otherUser");
        MarketScenario scenario = new MarketScenarioImpl(
                ScenarioType.RANGE,
                otherOwner,
                "ETH",
                clock.now(),
                clock
        );

        engine.scenarios.put(engine.keyOf(scenario), scenario);

        // L'user courant ne doit pas voir le scénario de l'autre
        List<MarketScenario> visible = engine.getActiveScenarios(owner, Instant.now(), Duration.ofDays(1));
        assertTrue(visible.isEmpty(), "Le scénario d'un autre owner ne doit pas être visible");

        engine.cleanup(Instant.now(), Duration.ofDays(0), executionMode); // durée 0 = expire tout
    }

}
