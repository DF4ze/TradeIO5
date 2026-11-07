package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.doctor.JobOutcome;
import fr.ses10doigts.tradeIO5.service.doctor.JobRunRecorder;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveQueryService;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Déclenchement manuel d'une passe du bench grandeur nature Rainbow (même patron que
 * {@link DecisionOrchestratorAdminController}). Réservé ROLE_ADMIN.
 * {@code POST /api/admin/rainbow-live/run[?day=YYYY-MM-DD]} : {@code day} (UTC) force le jour traité.
 * {@code POST /api/admin/rainbow-live/resync[?affectationId=]} : resynchronisation forcée de l'état moteur Trend Mix.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/rainbow-live")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class RainbowLiveAdminController {

    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;
    private final IAuthenticationFacade authenticationFacade;
    private final JobRunRecorder recorder;

    @PostMapping("/run")
    public ResponseEntity<RainbowLiveExecutionService.PassSummary> run(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        log.info("RainbowLiveAdminController : déclenchement manuel day={}", day);
        return ResponseEntity.ok(recorder.run(DoctorDefaults.JOB_RAINBOW_PASS, JobTrigger.MANUAL,
                () -> executionService.runPass(clock.now(), day), RainbowLiveExecutionService.PassSummary::errors));
    }

    /**
     * Resynchronisation FORCÉE de l'état moteur des presets Trend Mix actifs (ou du seul {@code affectationId}) jusqu'à la veille :
     * rejeu de la fenêtre sur les clôtures, sans run ni ordre (cf. {@link RainbowLiveExecutionService#resync}). Tracée au
     * journal admin (action RUN).
     */
    @PostMapping("/resync")
    public ResponseEntity<RainbowLiveExecutionService.ResyncSummary> resync(@RequestParam(required = false) Long affectationId) {
        String actor = authenticationFacade.getConnectedUser().getUsername();
        log.info("RainbowLiveAdminController : resynchronisation forcée affectationId={} par {}", affectationId, actor);
        return ResponseEntity.ok(executionService.resync(clock.now(), affectationId, true, actor, "resynchronisation forcée (admin)"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
