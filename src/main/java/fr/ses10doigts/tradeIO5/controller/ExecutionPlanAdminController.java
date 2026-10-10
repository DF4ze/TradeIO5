package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Déclenchement manuel du plan d'ordres dry-run (rien n'est envoyé). Réservé ROLE_ADMIN.
 * {@code POST /api/admin/execution/plan[?day=YYYY-MM-DD][&pass=T2355|T0005]} : {@code day} (UTC) par défaut = jour de la passe.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/execution")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ExecutionPlanAdminController {

    private final OrderPlanService planService;
    private final DomainClock clock;

    @PostMapping("/plan")
    public ResponseEntity<OrderPlanService.PlanSummary> plan(
            @RequestParam(defaultValue = "T2355") RainbowLivePass pass,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        LocalDate target = day != null ? day : RainbowLiveExecutionService.dayFor(pass, clock.now());
        log.info("ExecutionPlanAdminController : déclenchement manuel du plan jour={} passe={}", target, pass);
        return ResponseEntity.ok(planService.planAll(target, pass));
    }
}
