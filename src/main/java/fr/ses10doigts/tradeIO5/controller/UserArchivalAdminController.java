package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.tree.decision.ArchivalResult;
import fr.ses10doigts.tradeIO5.service.tree.decision.UserArchivalService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Déclenchement manuel de l'archivage sur inactivité prolongée ({@link UserArchivalService}), même
 * patron que {@link DecisionScenarioSnapshotAdminController} (étape 4) : le job planifié équivalent
 * ({@code UserArchivalJob}) est désactivé par défaut (Palier 3, étape 6), cet endpoint est le seul
 * déclenchement possible tant que ça reste le cas. Réservé ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/decision")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserArchivalAdminController {

    private static final Logger logger = LoggerFactory.getLogger(UserArchivalAdminController.class);

    private final UserArchivalService archivalService;

    @PostMapping("/archive")
    public ResponseEntity<ArchivalResult> triggerArchival() {
        logger.info("UserArchivalAdminController: déclenchement manuel de l'archivage sur inactivité.");
        return ResponseEntity.ok(archivalService.archiveInactiveUsers());
    }
}
