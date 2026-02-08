package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionCandidate;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class DecisionEngine {

    public Decision createDecision(DecisionCandidate candidate) {

        DecisionSnapshot snapshot = new DecisionSnapshot(
                UUID.randomUUID().toString(),
                candidate.symbol(),
                "MAIN_ACCOUNT",
                candidate.type(),
                Instant.now()
        );

        ActionStep step = new ActionStep(
                UUID.randomUUID().toString(),
                candidate.action(),
                candidate.quantity()
        );

        return new Decision(
                snapshot,
                List.of(step)
        );
    }
}
