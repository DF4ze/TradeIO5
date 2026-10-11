package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.admin.AdminResourceNotFoundException;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Mapping d'erreurs des endpoints de la page Admin (JSON {@code {"error": "..."}}). 409 plutôt que 401/403 pour la clé maître
 * absente : le front redirige vers /login sur 401/403.
 */
@RestControllerAdvice(assignableTypes = {AdminExecutionQueryController.class, AdminActionController.class,
        AdminCredentialController.class, AdminBindingController.class, OwnCredentialController.class,
        ExecutionPlanAdminController.class, AdminOrderTestController.class, AdminCatalogController.class})
public class AdminControllerAdvice {

    @ExceptionHandler(AdminResourceNotFoundException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> notFound(AdminResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MasterKeyMissingException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> noMasterKey(MasterKeyMissingException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(InstrumentLookupException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> catalogUnavailable(InstrumentLookupException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
