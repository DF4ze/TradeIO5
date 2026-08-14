package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.ActionStepExecutedCause;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.ActionStepFailedCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;
import lombok.Getter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Decision {
    @Getter
    private String id;
    @Getter
    private DecisionType type;
    @Getter
    private String symbol;
    @Getter
    private ScenarioOwner owner;

    @Getter
    private final DecisionSnapshot snapshot;
    @Getter
    private final List<ActionStep> steps;

    private final Set<String> executedStepIds = new HashSet<>();

    @Getter
    private DecisionStatus status = DecisionStatus.CREATED;
    private Instant createdAt;
    @Getter
    private Instant lastUpdatedAt;

    public Decision(DecisionSnapshot snapshot, List<ActionStep> steps) {
        symbol = snapshot.symbol();
        owner = snapshot.owner();
        type = snapshot.type();
        this.snapshot = snapshot;
        this.steps = List.copyOf(steps);
        this.lastUpdatedAt = snapshot.createdAt();
        this.createdAt = snapshot.createdAt(); // était déjà lu par generateDecisionId() mais jamais assigné
        id = generateDecisionId(); // déplacé en dernier : dépend de type/createdAt désormais assignés
    }

    /**
     * Reconstruction depuis un état déjà persisté (Palier 3, étape 4). Contrairement au constructeur
     * principal, ne génère pas de nouvel id : réinjecte celui fourni, pour que l'objet reconstruit
     * conserve l'identité sous laquelle ses DecisionEvent ont été persistés (DecisionEvent.getDecisionId()
     * utilise Decision.id, PAS DecisionSnapshot.decisionId() — les deux sont des identifiants
     * distincts, cf. décision actée en tête du prompt d'implémentation de cette étape). N'appelle
     * PAS le constructeur principal.
     */
    public Decision(
            String id,
            DecisionSnapshot snapshot,
            List<ActionStep> steps,
            DecisionStatus status,
            Set<String> executedStepIds,
            Instant lastUpdatedAt
    ) {
        this.id = id;
        this.symbol = snapshot.symbol();
        this.owner = snapshot.owner();
        this.type = snapshot.type();
        this.snapshot = snapshot;
        this.steps = List.copyOf(steps);
        this.status = status;
        this.executedStepIds.addAll(executedStepIds);
        this.lastUpdatedAt = lastUpdatedAt;
        this.createdAt = snapshot.createdAt();
    }

    public void apply(DecisionEvent event) {
        switch (event.getDecisionEventType()) {
            case ACTION_STEP_EXECUTED -> onStepExecuted(event);
            case ACTION_STEP_FAILED   -> onStepFailed(event);
        }
        lastUpdatedAt = event.getTimestamp();
    }

    // --- Gestion des events ---
    private void onStepExecuted(DecisionEvent event) {
        ActionStepExecutedCause cause = (ActionStepExecutedCause) event.getCause();
        executedStepIds.add(cause.stepId());

        if (isAllActionStepsExecuted()) {
            status = DecisionStatus.EXECUTED;
        }
    }

    private void onStepFailed(DecisionEvent event) {
        ActionStepFailedCause cause = (ActionStepFailedCause) event.getCause();
        executedStepIds.add(cause.stepId());
        status = DecisionStatus.ABORTED;
    }

    private boolean isAllActionStepsExecuted() {
        return steps.stream()
                .filter(step -> step.executionAction() != ExecutionAction.NO_OP)
                .allMatch(step -> executedStepIds.contains(step.stepId()));
    }

    /**
     * Copie défensive (Palier 3, étape 4 — service de photo quotidienne) : {@link #executedStepIds}
     * reste un état interne mutable, jamais exposé directement.
     */
    public Set<String> getExecutedStepIds() {
        return Set.copyOf(executedStepIds);
    }

    // -------- Helpers -------------
    private String generateDecisionId(){
        return "%s-%s-%s".formatted(
                type,
                createdAt,
                UUID.randomUUID().toString().substring(0, 8)
        );
    }

}
