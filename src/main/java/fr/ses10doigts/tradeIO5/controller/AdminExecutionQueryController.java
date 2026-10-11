package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminPlanDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminStateDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventPageDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.service.admin.AdminPlanQueryService;
import fr.ses10doigts.tradeIO5.service.admin.AdminStateService;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Lecture admin de l'exécution : état global, plans de tous les utilisateurs, audit d'un plan. Base uniquement. */
@RestController
@RequestMapping("/api/admin/execution")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminExecutionQueryController {

    private final AdminStateService stateService;
    private final AdminPlanQueryService planQueryService;

    @GetMapping("/state")
    public AdminStateDto state() {
        return stateService.state();
    }

    /** Plans courants de tous les utilisateurs (défaut : 7 derniers jours), filtrables par utilisateur et statut effectif. */
    @GetMapping("/plans")
    public List<AdminPlanDto> plans(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) Long userId,
                                    @RequestParam(required = false) PlanStatus status) {
        return planQueryService.plans(from, to, userId, status);
    }

    @GetMapping("/plans/{id}/events")
    public ResponseEntity<EventPageDto> events(@PathVariable Long id,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "" + ExecutionDefaults.DEFAULT_PAGE_SIZE) int size) {
        return planQueryService.events(id, page, size).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
