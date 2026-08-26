package fr.ses10doigts.tradeIO5.service.tree.scenario;

import java.time.Instant;

/**
 * Réponse JSON pour la lecture d'état des scénarios actifs (plan de test manuel Palier 3, 2026-08-17,
 * comble le manque relevé : {@code ScenarioEngine.getActiveScenarios}/{@code getAllActiveScenarios}
 * n'étaient exposées par aucun endpoint). Forme plate d'un {@link MarketScenario} : {@code
 * ScenarioOwner.getId()} appelé directement (pas de sérialisation Jackson de {@code ScenarioOwner}
 * lui-même) et {@code Optional<String> symbol} converti en {@code String} nullable — même raison que
 * {@code OpinionSignalResponse} (pas de {@code jackson-datatype-jdk8} dans ce projet).
 */
public record ScenarioSummaryResponse(
        String id,
        String owner,
        String scenarioType,
        String symbol,
        String scope,
        String status,
        double confidence,
        boolean stable,
        Instant createdAt,
        Instant lastUpdated
) {
    public static ScenarioSummaryResponse from(MarketScenario scenario) {
        var state = scenario.getState();
        return new ScenarioSummaryResponse(
                scenario.getId(),
                scenario.getOwner().getId(),
                scenario.getType() != null ? scenario.getType().name() : null,
                scenario.getSymbol().orElse(null),
                scenario.getScope() != null ? scenario.getScope().name() : null,
                state.getStatus() != null ? state.getStatus().name() : null,
                state.getConfidence(),
                state.isStable(),
                state.getCreatedAt(),
                state.getLastUpdated()
        );
    }
}
