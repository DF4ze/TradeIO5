package fr.ses10doigts.tradeIO5.service.tree.decision;

import java.time.Instant;

/**
 * Résultat d'une photo quotidienne (Palier 3, étape 4) : ce que {@link DecisionScenarioSnapshotService},
 * le job planifié et l'endpoint REST admin ont à logguer/retourner.
 */
public record SnapshotResult(int scenarioCount, int decisionCount, Instant snapshotAt) {}
