package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.ExecutionAction;

import java.math.BigDecimal;


/**
 * @param confidence confiance du scénario/signal à l'origine de cette étape (copiée depuis
 *                    {@code ActionIntent.confidence()} via {@code DecisionCandidate}, cf.
 *                    {@code DecisionEngine.createDecision}) — jusqu'ici calculée mais jamais
 *                    conservée sur l'agrégat (ajouté le 2026-09-03 à la demande de Clem, pour
 *                    lecture via {@code GET /api/admin/decision/decisions}).
 * @param reason      raison textuelle du scénario à l'origine (copiée depuis
 *                    {@code ActionIntent.reason()}) — même remarque que {@code confidence}.
 */
public record ActionStep(
        String stepId,
        ExecutionAction executionAction,
        BigDecimal quantity,
        Long walletId, // nullable : pas encore résolu tant que le Sizing (étude §4/§7) n'existe pas
        double confidence,
        String reason
) {
}
