package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.service.tree.decision.event.ActionStepExecutedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionTest {

    @Test
    void firstTest(){
        DecisionCandidate candidate = new DecisionCandidate(
                "BTC/EUR",
                DecisionType.ENTER,
                ExecutionAction.BUY,
                0.01
        );

        ActionStep step1 = new ActionStep("step1", ExecutionAction.BUY, 0.01);
        ActionStep step2 = new ActionStep("step2", ExecutionAction.NO_OP, 0);

        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                candidate.symbol(),
                "MAIN_ACCOUNT",
                candidate.type(),
                Instant.now()
        );

        Decision decision = new Decision(snapshot, List.of(step1, step2));

        // Simuler l’exécution
        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), step1.stepId(), Instant.now()));

        System.out.println(decision.getStatus()); // EXECUTED car step2 est NO_OP
        assertEquals(DecisionStatus.EXECUTED, decision.getStatus());

    }

    @Test
    void second(){
        ActionStep step1 = new ActionStep("s1", ExecutionAction.BUY, 0.01);
        ActionStep step2 = new ActionStep("s2", ExecutionAction.NO_OP, 0);
        ActionStep step3 = new ActionStep("s3", ExecutionAction.SELL, 0.01);

        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                "BTC/EUR",
                "MAIN_ACCOUNT",
                DecisionType.ENTER,
                Instant.now()
        );

        Decision decision = new Decision(snapshot, List.of(step1, step2, step3));

        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), "s1", Instant.now()));
        System.out.println(decision.getStatus()); // CREATED (s3 pas exécuté)
        assertEquals(DecisionStatus.CREATED, decision.getStatus());

        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), "s3", Instant.now()));
        System.out.println(decision.getStatus()); // EXECUTED
        assertEquals(DecisionStatus.EXECUTED, decision.getStatus());

    }

    @Test
    void third(){
        ActionStep step1 = new ActionStep("s1", ExecutionAction.BUY, 0.01);
        ActionStep step2 = new ActionStep("s2", ExecutionAction.BUY, 0.01);
        ActionStep step3 = new ActionStep("s3", ExecutionAction.SELL, 0.02);

        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                "BTC/EUR",
                "MAIN_ACCOUNT",
                DecisionType.ENTER,
                Instant.now()
        );

        Decision decision = new Decision(snapshot, List.of(step1, step2, step3));

        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), "s1", Instant.now()));
        System.out.println(decision.getStatus()); // CREATED (s3 pas exécuté)
        assertEquals(DecisionStatus.CREATED, decision.getStatus());

        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), "s3", Instant.now()));
        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), "s4", Instant.now()));
        decision.apply(new ActionStepExecutedEvent(decision.getSnapshot().decisionId(), "s2", Instant.now()));

        System.out.println(decision.getStatus()); // EXECUTED
        assertEquals(DecisionStatus.EXECUTED, decision.getStatus());

    }
}