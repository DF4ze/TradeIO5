package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConflictException;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Mapping d'erreurs limité à {@link RainbowLiveController} (pas de handler global dans le projet). */
@RestControllerAdvice(assignableTypes = RainbowLiveController.class)
public class RainbowLiveControllerAdvice {

    public record ErrorResponse(String error) {
    }

    @ExceptionHandler(RainbowLivePresetNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RainbowLivePresetNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(RainbowLivePresetConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(RainbowLivePresetConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }
}
