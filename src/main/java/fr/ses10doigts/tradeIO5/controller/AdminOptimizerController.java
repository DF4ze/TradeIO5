package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerRunRequest;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerService;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerService.OptimizerException;
import fr.ses10doigts.tradeIO5.service.optimizer.OptimizerService.RunView;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Optimiseur de politique (admin) : {@code POST} lance un run asynchrone (202), {@code GET} suit sa progression puis renvoie le
 * rapport JSON, {@code DELETE} l'annule. ROLE_ADMIN ; absent (404) tant que {@code tradeio.optimizer.enabled} n'est pas {@code true}.
 */
@RestController
@RequestMapping("/api/admin/optimizer/runs")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = OptimizerDefaults.ENABLED_PROPERTY, havingValue = "true")
@RequiredArgsConstructor
public class AdminOptimizerController {

    private final OptimizerService service;
    private final IAuthenticationFacade authenticationFacade;

    @PostMapping
    public ResponseEntity<RunView> start(@RequestBody OptimizerRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(authenticationFacade.getConnectedUser(), request));
    }

    @GetMapping("/{id}")
    public RunView get(@PathVariable String id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public RunView cancel(@PathVariable String id) {
        return service.cancel(id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(OptimizerException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> business(OptimizerException e) {
        HttpStatus status = e.code() == OptimizerException.Code.BUSY ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        return ResponseEntity.status(status).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
