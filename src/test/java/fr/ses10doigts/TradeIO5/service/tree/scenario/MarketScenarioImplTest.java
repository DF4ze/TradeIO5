package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.MarketAction;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MarketScenarioImplTest {
    private FixedDomainClock clock;
    private MarketScenarioImpl scenario;
    private ScenarioOwner owner1;

    @BeforeEach
    void setUp() {
        clock = new FixedDomainClock(Instant.parse("2026-01-29T20:00:00Z"));

        owner1 = ScenarioOwner.user("OWNER1");

        scenario = new MarketScenarioImpl(
                ScenarioType.TREND_UP,
                owner1,
                "BTCUSD",
                Instant.parse("2026-01-29T19:00:00Z"),
                clock
        );

    }

    @Test
    void testInitialState() {
        assertEquals(ScenarioStatus.INITIAL, scenario.getState().getStatus());
        assertEquals(SignalType.NEUTRAL, scenario.getState().getSignal());
        assertEquals(0.0, scenario.getState().getConfidence());
    }

    @Test
    void testObserveMutateAndConfirming() {
        // Opinion avec même signal BULLISH
        scenario.observe( opResult(SignalType.BULLISH, 0.8), context(clock, owner1));

        assertTrue(scenario.getState().getConfidence() > 0); // confiance augmentée
        assertEquals(SignalType.BULLISH, scenario.getState().getSignal());
    }

    @Test
    void testProposeIntentWhenValidated() {
        // Forcer le scénario validé et stable
        scenario.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));
        scenario.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));
        scenario.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));
        scenario.observe(opResult(SignalType.BULLISH, 0.95), context(clock, owner1));

        Optional<ActionIntent> intent = scenario.proposeIntent();
        assertTrue(intent.isPresent());
        assertEquals(MarketAction.BUY, intent.get().action());
    }

    @Test
    void testEnrichFromRefused() {
        MarketScenarioImpl other = new MarketScenarioImpl(
                ScenarioType.TREND_UP,
                ScenarioOwner.user("OWNER2"),
                "BTCUSD",
                Instant.parse("2026-01-29T19:30:00Z"),
                clock
        );
        other.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1)); //! Owner?

        scenario.enrichFrom(other);

        assertFalse(scenario.getState().getConfidence() > 0);
    }

    @Test
    void testEnrichFromAccepted() {
        MarketScenarioImpl other = new MarketScenarioImpl(
                ScenarioType.TREND_UP,
                owner1,
                null,
                Instant.parse("2026-01-29T19:30:00Z"),
                clock
        );
        other.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1));
        other.observe(opResult(SignalType.BULLISH, 0.8), context(clock, owner1));

        System.out.println("Current Scenario : ");

        scenario.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));
        scenario.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));

        double initialConfidence = scenario.getState().getConfidence();

        System.out.println("Enrich Scenario : ");
        scenario.enrichFrom(other);

        assertTrue(scenario.getState().getConfidence() > initialConfidence);
    }

    @Test
    void testExpiration() {
        clock.set(Instant.parse("2025-01-29T19:30:00Z"));
        scenario.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));

        clock.set(Instant.parse("2026-01-29T19:30:00Z"));
        scenario.observe(opResult(SignalType.BULLISH, 0.5), context(clock, owner1));

        assertEquals(ScenarioStatus.EXPIRED, scenario.getState().getStatus());
    }

    @Test
    void testInvalidation() {
        // Forcer confidence à 0
        scenario.getState().setConfidence(0.0);
        scenario.observe(opResult(SignalType.BULLISH, 0.1), context(clock, owner1));
        scenario.observe(opResult(SignalType.BEARISH, 0.85), context(clock, owner1));

        assertEquals(ScenarioStatus.INVALIDATED, scenario.getState().getStatus());
    }

    private static MarketOpinionResult opResult( SignalType type, double confidence ){
        return new MarketOpinionResult(
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
                clock.now(),
                new ArrayList<>());
    }
}