package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.MarketAction;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.DefaultScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.InMemoryEventStore;
import fr.ses10doigts.tradeIO5.service.tree.scenario.event.ScenarioEventEmitter;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultMarketScenarioTest {
    private FixedDomainClock clock;
    private DefaultMarketScenario scenario1;
    private DefaultMarketScenario scenario2;
    private DefaultMarketScenario scenario3;
    private ScenarioOwner owner1;

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(Instant.parse("2026-01-29T20:00:00Z"));

        owner1 = ScenarioOwner.user("OWNER1");
        ScenarioOwner owner2 = ScenarioOwner.user("OWNER2");

        ScenarioDefinition def1 = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner1,
                Optional.of("BTCUSD"),
                Instant.parse("2026-01-29T19:00:00Z")
        );
        ScenarioDefinition def2 = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner2,
                Optional.of("BTCUSD"),
                Instant.parse("2026-01-29T19:30:00Z")
        );
        ScenarioDefinition def3 = new ScenarioDefinition(
                ScenarioType.TREND_UP,
                owner2,
                Optional.empty(),
                Instant.parse("2026-01-29T19:30:00Z")
        );

        ScenarioEventEmitter emitter = new DefaultScenarioEventEmitter(new InMemoryEventStore());

        scenario1 = new DefaultMarketScenario(def1, emitter);
        scenario2 = new DefaultMarketScenario(def2, emitter);
        scenario3 = new DefaultMarketScenario(def3, emitter);

    }

    @Test
    void testInitialState() {
        assertEquals(ScenarioStatus.INITIAL, scenario1.getState().getStatus());
        assertEquals(SignalType.NEUTRAL, scenario1.getState().getSignal());
        assertEquals(0.0, scenario1.getState().getConfidence());
    }

    @Test
    void testObserveMutateAndConfirming() {
        // Opinion avec même signal BULLISH
        scenario1.observe( opResult(SignalType.BULLISH, 0.8), context(clock, owner1));

        assertTrue(scenario1.getState().getConfidence() > 0); // confiance augmentée
        assertEquals(SignalType.BULLISH, scenario1.getState().getSignal());
    }

    @Test
    void testProposeIntentWhenValidated() {
        // Forcer le scénario validé et stable
        scenario1.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));
        scenario1.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));
        scenario1.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));
        scenario1.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));

        Optional<ActionIntent> intent = scenario1.proposeIntent(clock.now());
        assertTrue(intent.isPresent());
        assertEquals(MarketAction.BUY, intent.get().action());
    }

    @Test
    void testEnrichFromRefused() {

        scenario2.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1)); //! Owner?

        scenario1.enrichFrom( scenario2, clock.now() );

        assertFalse(scenario1.getState().getConfidence() > 0);
    }

    @Test
    void testEnrichFromAccepted() {

        scenario3.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1));
        scenario3.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1));
        scenario3.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1));

        System.out.println("Current Scenario : ");

        scenario1.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));
        scenario1.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));
        scenario1.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));

        double initialConfidence = scenario1.getState().getConfidence();

        System.out.println("Enrich Scenario : ");
        scenario1.enrichFrom(scenario3, clock.now());

        assertTrue(scenario1.getState().getConfidence() > initialConfidence);
    }

    @Test
    void testExpiration() {
        clock.set(Instant.parse("2025-01-29T19:30:00Z"));
        scenario1.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));

        clock.set(Instant.parse("2026-01-29T19:30:00Z"));
        scenario1.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));

        assertEquals(ScenarioStatus.EXPIRED, scenario1.getState().getStatus());
    }

    @Test
    void testInvalidation() {
        // Forcer confidence à 0
        scenario1.getState().setConfidence(0.0);
        scenario1.observe(opResult(SignalType.BULLISH, 0.1), context(clock, owner1));
        scenario1.observe(opResult(SignalType.BEARISH, 0.85), context(clock, owner1));

        assertEquals(ScenarioStatus.INVALIDATED, scenario1.getState().getStatus());
    }

    private static MarketOpinionResult opResult( SignalType type, double confidence ){
        return new MarketOpinionResult(
                "MarketOpinionId",
                type,
                type,
                confidence,
                confidence,
                new ArrayList<>(),
                "");
    }

    private static ScenarioContext context( DomainClock clock, ScenarioOwner owner ){
        return new ScenarioContext(
                owner,
                Optional.of("BTC"),
                clock,
                new ArrayList<>());
    }
}