package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.configuration.initializer.ExecutionControlInitializer;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.service.admin.AdminLimitsService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService.Limits;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plafonds de dépense et seuils Fee Test, réservé ROLE_ADMIN. Le {@code GET} renvoie, par paramètre, la valeur, le défaut codé et
 * la borne haute ; le {@code PUT} remplace le jeu complet (validé) et écrit une ligne {@code LIMITS} au journal.
 */
@RestController
@RequestMapping("/api/admin/limits")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminLimitsController {

    private final ExecutionControlService controlService;
    private final AdminLimitsService adminService;
    private final IAuthenticationFacade authenticationFacade;

    /** Un paramètre : valeur courante, défaut codé et borne haute (la borne basse est toujours exclusive de 0, hors Fee Test). */
    public record ParameterDto(BigDecimal value, BigDecimal defaultValue, BigDecimal max) {
    }

    public record LimitsDto(Map<String, ParameterDto> parameters, Instant updatedAt, String updatedBy) {
        static LimitsDto of(ExecutionControl c) {
            Limits l = Limits.of(c);
            Map<String, ParameterDto> p = new LinkedHashMap<>();
            p.put("maxOrderMultiplier", new ParameterDto(l.maxOrderMultiplier(), ExecutionControlService.DEFAULT_MAX_ORDER_MULTIPLIER,
                    ExecutionControlService.MAX_MULTIPLIER));
            p.put("maxDayMultiplier", new ParameterDto(l.maxDayMultiplier(), ExecutionControlService.DEFAULT_MAX_DAY_MULTIPLIER,
                    ExecutionControlService.MAX_MULTIPLIER));
            p.put("absoluteMaxOrderUsd", new ParameterDto(l.absoluteMaxOrderUsd(), ExecutionControlInitializer.DEFAULT_ABSOLUTE_MAX_ORDER_USD,
                    ExecutionControlService.MAX_ABSOLUTE_USD));
            p.put("absoluteMaxDayUsd", new ParameterDto(l.absoluteMaxDayUsd(), ExecutionControlInitializer.DEFAULT_ABSOLUTE_MAX_DAY_USD,
                    ExecutionControlService.MAX_ABSOLUTE_USD));
            p.put("feeWarnPct", new ParameterDto(l.feeWarnPct(), ExecutionControlInitializer.DEFAULT_FEE_TEST_WARN_PCT,
                    ExecutionControlService.MAX_FEE_PCT));
            p.put("feeRedPct", new ParameterDto(l.feeRedPct(), ExecutionControlInitializer.DEFAULT_FEE_TEST_RED_PCT,
                    ExecutionControlService.MAX_FEE_PCT));
            return new LimitsDto(p, c.getUpdatedAt(), c.getUpdatedBy());
        }
    }

    @GetMapping
    public LimitsDto get() {
        return LimitsDto.of(controlService.current());
    }

    @PutMapping
    public LimitsDto put(@RequestBody Limits request) {
        return LimitsDto.of(adminService.update(request, authenticationFacade.getConnectedUser().getUsername()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
