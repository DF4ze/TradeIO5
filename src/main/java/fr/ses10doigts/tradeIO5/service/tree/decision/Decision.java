package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import fr.ses10doigts.tradeIO5.service.tree.decision.event.ActionStepExecutedEvent;
import fr.ses10doigts.tradeIO5.service.tree.decision.event.ActionStepFailedEvent;
import fr.ses10doigts.tradeIO5.service.tree.decision.event.DecisionEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Decision {

    @Getter
    private final DecisionSnapshot snapshot;
    private final List<ActionStep> steps;

    private final Set<String> executedStepIds = new HashSet<>();

    @Getter
    private DecisionStatus status = DecisionStatus.CREATED;
    private Instant lastUpdatedAt;

    public Decision(DecisionSnapshot snapshot, List<ActionStep> steps) {
        this.snapshot = snapshot;
        this.steps = List.copyOf(steps);
        this.lastUpdatedAt = snapshot.createdAt();
    }

    public void apply(DecisionEvent event) {
        if (event instanceof ActionStepExecutedEvent executed) {
            handleActionStepExecuted(executed);
        } else if (event instanceof ActionStepFailedEvent failed) {
            handleActionStepFailed(failed);
        }
        lastUpdatedAt = event.occurredAt();
    }

    // --- Gestion des events ---
    private void handleActionStepExecuted(ActionStepExecutedEvent event) {
        executedStepIds.add(event.actionStepId());
        // marquer le step comme exécuté (optionnel : on pourrait ajouter un Set des steps exécutés)
        if (allActionStepsExecuted()) {
            status = DecisionStatus.EXECUTED;
        }
    }

    private void handleActionStepFailed(ActionStepFailedEvent event) {
        executedStepIds.add(event.actionStepId());
        status = DecisionStatus.ABORTED;
    }

    private boolean allActionStepsExecuted() {
        return steps.stream()
                .filter(step -> step.executionAction() != ExecutionAction.NO_OP)
                .allMatch(step -> executedStepIds.contains(step.stepId()));
    }
}
