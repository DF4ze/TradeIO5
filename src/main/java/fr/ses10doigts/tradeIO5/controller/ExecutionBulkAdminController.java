package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService.BulkAction;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService.BulkResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Actions groupées de la Console sur des plans : {@code POST /api/admin/execution/plans/bulk/{reset|arm|run|reconcile}}.
 * Réservé ROLE_ADMIN. Éligibilité et garde-fous côté serveur ({@link AdminBulkPlanService}) ; {@code dryRun} prévisualise sans rien
 * modifier ; {@code run} réel exige {@code confirm = "EXECUTER"}.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/execution/plans/bulk")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ExecutionBulkAdminController {

    private final AdminBulkPlanService bulkService;
    private final IAuthenticationFacade authenticationFacade;

    public record BulkRequest(List<Long> planIds, boolean dryRun, String confirm) {
    }

    @PostMapping("/{action}")
    public ResponseEntity<BulkResult> bulk(@PathVariable String action, @RequestBody BulkRequest request) {
        BulkAction parsed = BulkAction.valueOf(action.toUpperCase(Locale.ROOT)); // inconnue => IllegalArgumentException => 400
        log.info("ExecutionBulkAdminController : lot {} dryRun={}", parsed, request.dryRun());
        return ResponseEntity.ok(bulkService.execute(parsed, request.planIds(), request.dryRun(), request.confirm(),
                authenticationFacade.getConnectedUser().getUsername()));
    }
}
