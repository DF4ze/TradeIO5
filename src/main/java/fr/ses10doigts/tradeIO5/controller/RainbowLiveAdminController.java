package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
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
 * Déclenchement manuel d'une passe du bench grandeur nature Rainbow (même patron que
 * {@link DecisionOrchestratorAdminController}). Réservé ROLE_ADMIN.
 * {@code POST /api/admin/rainbow-live/run?pass=T2355|T0005[&day=YYYY-MM-DD]} : {@code day} (UTC) force le jour traité.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/rainbow-live")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class RainbowLiveAdminController {

    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;

    @PostMapping("/run")
    public ResponseEntity<RainbowLiveExecutionService.PassSummary> run(
            @RequestParam RainbowLivePass pass,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        log.info("RainbowLiveAdminController : déclenchement manuel passe={} day={}", pass, day);
        return ResponseEntity.ok(executionService.runPass(pass, clock.now(), day));
    }
}
