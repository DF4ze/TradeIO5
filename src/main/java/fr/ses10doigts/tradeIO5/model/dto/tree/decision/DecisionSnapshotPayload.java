package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * DTO de transport pour {@code DecisionSnapshotEntity.snapshotJson} (Palier 3, étape 4) : composite
 * sérialisé en JSON plutôt qu'éclaté en colonnes séparées, même convention que {@code
 * EventEntity.payload} / {@code ScenarioSnapshotEntity.stateJson}.
 */
public record DecisionSnapshotPayload(
        DecisionSnapshot snapshot,
        List<ActionStep> steps,
        Set<String> executedStepIds,
        Instant lastUpdatedAt
) {}
