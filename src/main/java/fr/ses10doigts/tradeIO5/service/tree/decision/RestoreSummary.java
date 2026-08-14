package fr.ses10doigts.tradeIO5.service.tree.decision;

/**
 * Résultat d'une restauration (totale ou owner-scopée), Palier 3, étape 6. Même esprit que
 * {@link SnapshotResult} de l'étape 4.
 */
public record RestoreSummary(int scenarioCount, int decisionCount) {}
