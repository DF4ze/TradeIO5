package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionOrchestrator;
import fr.ses10doigts.tradeIO5.service.tree.decision.OrchestrationResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Déclenchement manuel du cycle d'orchestration ({@link DecisionOrchestrator}), même patron que
 * {@link UserArchivalAdminController} (étape 6) : le job planifié équivalent
 * ({@code DecisionOrchestratorJob}) est désactivé par défaut (Palier 3, étape 7), cet endpoint est
 * le seul déclenchement possible tant que ça reste le cas (en plus du cron, une fois activé).
 * Réservé ROLE_ADMIN. Même préfixe {@code /api/admin/decision} que
 * {@code DecisionScenarioSnapshotAdminController}/{@code UserArchivalAdminController}, chemin
 * distinct {@code /orchestrate}.
 */
@RestController
@RequestMapping("/api/admin/decision")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DecisionOrchestratorAdminController {

    private static final Logger logger = LoggerFactory.getLogger(DecisionOrchestratorAdminController.class);

    private final DecisionOrchestrator orchestrator;

    @PostMapping("/orchestrate")
    public ResponseEntity<OrchestrationResult> triggerCycle() {
        logger.info("DecisionOrchestratorAdminController: déclenchement manuel du cycle d'orchestration.");
        return ResponseEntity.ok(orchestrator.runCycle());
    }
}
