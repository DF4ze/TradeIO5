package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import fr.ses10doigts.tradeIO5.service.execution.run.ExecutionResult;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Pilotage de l'exécution réelle, réservé ROLE_ADMIN : lancement d'un plan, kill switch, armement du 1ᵉʳ ordre d'un binding,
 * credentials TRADE. Aucun secret en réponse ni dans les logs. Sans mode LIVE, {@code run} n'envoie rien.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/execution")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ExecutionAdminController {

    private final OrderExecutor executor;
    private final ExecutionControlService controlService;
    private final RainbowLiveBindingService bindingService;
    private final TradeCredentialService credentialService;
    private final UserRepository userRepository;
    private final IAuthenticationFacade authenticationFacade;

    public record KillSwitchRequest(boolean engaged) {
    }

    public record ControlDto(boolean killSwitch, java.math.BigDecimal maxOrderMultiplier, java.math.BigDecimal maxDayMultiplier,
                             Instant updatedAt, String updatedBy) {
        static ControlDto of(ExecutionControl c) {
            return new ControlDto(c.isKillSwitch(), c.getMaxOrderMultiplier(), c.getMaxDayMultiplier(), c.getUpdatedAt(),
                    c.getUpdatedBy());
        }
    }

    /** Credential TRADE : {@code toString} masqué (jamais de secret dans un log d'exception ou de requête). */
    public record TradeCredentialRequest(Long userId, WebProviderCode provider, String apiKey, String secretKey,
                                         String passphrase, Boolean enabled) {
        @Override
        public String toString() {
            return "TradeCredentialRequest[userId=" + userId + ", provider=" + provider + "]";
        }
    }

    public record TradeCredentialDto(Long id, Long userId, String provider, String scope, boolean enabled) {
    }

    @PostMapping("/run")
    public ResponseEntity<ExecutionResult> run(@RequestParam Long planId) {
        log.info("ExecutionAdminController : exécution manuelle du plan {}", planId);
        return ResponseEntity.ok(executor.execute(planId));
    }

    @GetMapping("/control")
    public ControlDto control() {
        return ControlDto.of(controlService.current());
    }

    @PutMapping("/kill-switch")
    public ControlDto killSwitch(@RequestBody KillSwitchRequest request) {
        return ControlDto.of(controlService.setKillSwitch(request.engaged(), authenticationFacade.getConnectedUser().getUsername()));
    }

    @PostMapping("/bindings/{id}/arm")
    public ResponseEntity<Void> arm(@PathVariable Long id) {
        bindingService.arm(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/credentials")
    public ResponseEntity<TradeCredentialDto> storeCredential(@RequestBody TradeCredentialRequest request) {
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur inconnu : " + request.userId()));
        ApiCredential saved = credentialService.store(user, request.provider(), request.apiKey(), request.secretKey(),
                request.passphrase(), request.enabled() == null || request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TradeCredentialDto(saved.getId(), user.getId(),
                request.provider().name(), saved.getScope().name(), saved.isEnabled()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MasterKeyMissingException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> noMasterKey(MasterKeyMissingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
