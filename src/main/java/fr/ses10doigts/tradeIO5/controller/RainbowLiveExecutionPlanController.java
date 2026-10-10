package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.PlanDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.execution.plan.OrderPlanQueryService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Plans d'ordres (simulation, rien n'est envoyé) de l'utilisateur connecté, tels qu'enregistrés en base. Propriétaire
 * seulement ; aucun appel exchange à la lecture.
 */
@RestController
@RequestMapping("/api/rainbow-live/execution-plans")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveExecutionPlanController {

    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final OrderPlanQueryService queryService;
    private final IAuthenticationFacade authenticationFacade;
    private final DomainClock clock;

    /** Plans entre {@code from} et {@code to} inclus (défaut : 30 derniers jours). */
    @GetMapping
    public List<PlanDto> plans(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : clock.now().atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        return queryService.plans(authenticationFacade.getConnectedUser(), start, end);
    }

    /** Audit du plan (lecture seule, paginé, du plus ancien au plus récent) ; plan d'un autre utilisateur ou inexistant => 404. */
    @GetMapping("/{id}/events")
    public ResponseEntity<fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventPageDto> events(
            @org.springframework.web.bind.annotation.PathVariable Long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queryService.events(authenticationFacade.getConnectedUser(), id, page, size).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Plan courant le plus récent ; 204 si aucun. */
    @GetMapping("/latest")
    public ResponseEntity<PlanDto> latest() {
        return queryService.latest(authenticationFacade.getConnectedUser()).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
