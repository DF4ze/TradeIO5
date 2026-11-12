package fr.ses10doigts.tradeIO5.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Erreurs de {@link AdminUserAccessController} au format de la page Admin : JSON {@code {"error": "..."}} (statuts 400/404/409/502, jamais 401/403). */
@RestControllerAdvice(assignableTypes = AdminUserAccessController.class)
public class AdminUserAccessControllerAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getReason()));
    }
}
