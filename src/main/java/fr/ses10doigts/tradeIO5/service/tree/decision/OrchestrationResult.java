package fr.ses10doigts.tradeIO5.service.tree.decision;

import java.time.Instant;

/**
 * Résultat d'un cycle d'orchestration (Palier 3, étape 7) : ce que {@link DecisionOrchestrator}, le
 * job planifié et l'endpoint REST admin ont à logguer/retourner. Même esprit que
 * {@link ArchivalResult} de l'étape 6.
 */
public record OrchestrationResult(
        int signalsComputed,
        int activeUsersFound,
        int usersProcessed,
        int usersSkippedLocked,
        Instant runAt
) {}
