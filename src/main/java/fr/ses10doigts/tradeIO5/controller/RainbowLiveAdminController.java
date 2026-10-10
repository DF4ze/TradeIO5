package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.StrategyDto;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowAssetStrategyService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveQueryService;
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
    private final RainbowAssetStrategyService strategyService;

    @PostMapping("/run")
    public ResponseEntity<RainbowLiveExecutionService.PassSummary> run(
            @RequestParam RainbowLivePass pass,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        log.info("RainbowLiveAdminController : déclenchement manuel passe={} day={}", pass, day);
        return ResponseEntity.ok(executionService.runPass(pass, clock.now(), day));
    }

    /**
     * Modification d'une stratégie Actif par System : fenêtre + réglages Trend (jeux Bear/Bull inclus), validations
     * identiques à celles des presets. {@code revision} + 1 ; effet à la prochaine passe chez les users qui la suivent.
     */
    @PutMapping("/strategies/{id}")
    public StrategyDto updateStrategy(@PathVariable Long id, @RequestBody RainbowAssetStrategyService.UpdateRequest request) {
        log.info("RainbowLiveAdminController : modification de la stratégie id={}", id);
        return RainbowLiveQueryService.toStrategyDto(strategyService.update(id, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
