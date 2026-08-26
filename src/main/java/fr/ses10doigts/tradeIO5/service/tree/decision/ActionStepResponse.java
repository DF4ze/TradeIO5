package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;

import java.math.BigDecimal;

/** Réponse JSON pour un {@link ActionStep} (plan de test manuel Palier 3, 2026-08-17). */
public record ActionStepResponse(
        String stepId,
        String executionAction,
        BigDecimal quantity,
        Long walletId
) {
    public static ActionStepResponse from(ActionStep step) {
        return new ActionStepResponse(
                step.stepId(),
                step.executionAction() != null ? step.executionAction().name() : null,
                step.quantity(),
                step.walletId()
        );
    }
}
