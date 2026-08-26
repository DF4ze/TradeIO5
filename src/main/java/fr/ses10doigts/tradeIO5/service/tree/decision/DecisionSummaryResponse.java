package fr.ses10doigts.tradeIO5.service.tree.decision;

import java.util.List;

/**
 * Réponse JSON pour la lecture d'état des décisions actives (plan de test manuel Palier 3, 2026-08-17,
 * comble le manque relevé : {@code DecisionEngine.getAllActiveDecisions} n'était exposée par aucun
 * endpoint). Forme plate d'une {@link Decision} : {@code ScenarioOwner.getId()} appelé directement
 * (pas de sérialisation Jackson de {@code ScenarioOwner} lui-même).
 */
public record DecisionSummaryResponse(
        String id,
        String owner,
        String symbol,
        String type,
        String status,
        List<ActionStepResponse> steps
) {
    public static DecisionSummaryResponse from(Decision decision) {
        return new DecisionSummaryResponse(
                decision.getId(),
                decision.getOwner().getId(),
                decision.getSymbol(),
                decision.getType() != null ? decision.getType().name() : null,
                decision.getStatus() != null ? decision.getStatus().name() : null,
                decision.getSteps().stream().map(ActionStepResponse::from).toList()
        );
    }
}
