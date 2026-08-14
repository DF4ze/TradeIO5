package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.tree.decision.DecisionScenarioSnapshotService;
import fr.ses10doigts.tradeIO5.service.tree.decision.SnapshotResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Déclenchement manuel de la photo quotidienne ({@link DecisionScenarioSnapshotService}), même
 * patron que {@link EtfFlowAdminController} : le job planifié équivalent
 * ({@code DecisionScenarioSnapshotJob}) est désactivé par défaut (Palier 3, étape 4), cet endpoint
 * est le seul déclenchement possible tant que ça reste le cas. Réservé ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/decision")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DecisionScenarioSnapshotAdminController {

    private static final Logger logger = LoggerFactory.getLogger(DecisionScenarioSnapshotAdminController.class);

    private final DecisionScenarioSnapshotService snapshotService;

    @PostMapping("/snapshot")
    public ResponseEntity<SnapshotResult> triggerSnapshot() {
        logger.info("DecisionScenarioSnapshotAdminController: déclenchement manuel de la photo quotidienne.");
        return ResponseEntity.ok(snapshotService.takeSnapshot());
    }
}
