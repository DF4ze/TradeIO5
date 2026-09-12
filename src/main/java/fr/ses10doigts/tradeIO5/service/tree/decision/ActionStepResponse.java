package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep;

import java.math.BigDecimal;

/**
 * Réponse JSON pour un {@link ActionStep} (plan de test manuel Palier 3, 2026-08-17).
 * {@code confidence}/{@code reason} ajoutés le 2026-09-03 (Clem) : jusqu'ici calculés par le
 * scénario à l'origine mais jamais exposés, pour permettre de rapprocher une Decision générée par
 * le cron de ce qui est visible sur les graphiques.
 */
public record ActionStepResponse(
        String stepId,
        String executionAction,
        BigDecimal quantity,
        Long walletId,
        double confidence,
        String reason
) {
    public static ActionStepResponse from(ActionStep step) {
        return new ActionStepResponse(
                step.stepId(),
                step.executionAction() != null ? step.executionAction().name() : null,
                step.quantity(),
                step.walletId(),
                step.confidence(),
                step.reason()
        );
    }
}
