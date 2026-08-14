package fr.ses10doigts.tradeIO5.service.tree.decision;

import java.time.Instant;

/**
 * Résultat d'un archivage sur inactivité prolongée (Palier 3, étape 6) : ce que {@link
 * UserArchivalService}, le job planifié et l'endpoint REST admin ont à logguer/retourner. Même
 * esprit que {@link SnapshotResult} de l'étape 4.
 */
public record ArchivalResult(int archivedCount, Instant archivedAt) {}
