package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.ActionStepExecutedCause;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionTest {

    ScenarioOwner owner1 = ScenarioOwner.user("user1");
    DomainClock clock = new FixedDomainClock(Instant.parse("2026-01-29T20:00:00Z"));

    DecisionCandidate candidate = new DecisionCandidate(
            "BTC/EUR",
            DecisionType.ENTER,
            ExecutionAction.BUY,
            0.01,
            BigDecimal.ZERO,
            "Parceque",
            owner1,
            clock.now()
    );

    ActionStep step1 = new ActionStep("step1", ExecutionAction.BUY, new BigDecimal("0.01"));
    ActionStep step2 = new ActionStep("step2", ExecutionAction.NO_OP, BigDecimal.ZERO);
    ActionStep step3 = new ActionStep("s3", ExecutionAction.SELL, new BigDecimal("0.01"));
    List<ActionStep> steps2 = List.of(step1, step2);
    List<ActionStep> steps3 = List.of(step1, step2, step3);

    DecisionSnapshot snapshot = new DecisionSnapshot(
            UUID.randomUUID().toString(),
            candidate.symbol(),
            owner1,
            candidate.type(),
            Instant.now()
    );

    EventBus eventBus = new EventBus();

    Decision decision2 = new Decision(snapshot, steps2, eventBus);
    Decision decision3 = new Decision(snapshot, steps3, eventBus);



    @Test
    void firstTest(){
        decision2.apply(new DecisionEvent(
                decision2,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(step1.stepId(), step1.executionAction(), step1.quantity()),
                clock.now()
        ));

        System.out.println(decision2.getStatus()); // EXECUTED car step2 est NO_OP
        assertEquals(DecisionStatus.EXECUTED, decision2.getStatus());

    }

    @Test
    void second(){

        decision3.apply(new DecisionEvent(
                decision3,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(step1.stepId(), step1.executionAction(), step1.quantity()),
                clock.now()
        ));

        System.out.println(decision3.getStatus()); // CREATED (s3 pas exécuté)
        assertEquals(DecisionStatus.CREATED, decision3.getStatus());

        decision3.apply(new DecisionEvent(
                decision3,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(step3.stepId(), step3.executionAction(), step3.quantity()),
                clock.now()
        ));
        System.out.println(decision3.getStatus()); // EXECUTED
        assertEquals(DecisionStatus.EXECUTED, decision3.getStatus());

    }

    @Test
    void third(){

        decision3.apply(new DecisionEvent(
                decision3,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(step3.stepId(), step3.executionAction(), step3.quantity()),
                clock.now()
        ));
        System.out.println(decision3.getStatus()); // CREATED (s3 pas exécuté)
        assertEquals(DecisionStatus.CREATED, decision3.getStatus());

        decision3.apply(new DecisionEvent(
                decision3,
                DecisionEventType.ACTION_STEP_EXECUTED,
                new ActionStepExecutedCause(step1.stepId(), step1.executionAction(), step1.quantity()),
                clock.now()
        ));

        System.out.println(decision3.getStatus()); // EXECUTED
        assertEquals(DecisionStatus.EXECUTED, decision3.getStatus());

    }
}