package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Mapping d'erreurs des contrôleurs {@code /api/expert/**} (pas de handler global) : statut + code stable + détails. */
@RestControllerAdvice(assignableTypes = {ExpertAssetController.class, ExpertTrendPresetController.class,
        ExpertRainbowPresetController.class, ExpertAffectationController.class,
        ExpertSimulationController.class})
public class ExpertControllerAdvice {

    /** {@code code} stable ; {@code details} = champs en erreur (400), Affectations utilisatrices (409 PRESET_IN_USE), révision courante (409). */
    public record ExpertErrorResponse(String error, String code, Object details) {
    }

    @ExceptionHandler(ExpertApiException.class)
    public ResponseEntity<ExpertErrorResponse> api(ExpertApiException e) {
        return ResponseEntity.status(e.getCode().status()).body(new ExpertErrorResponse(e.getMessage(), e.getCode().name(), e.getDetails()));
    }

    @ExceptionHandler(ExpertValidationException.class)
    public ResponseEntity<ExpertErrorResponse> validation(ExpertValidationException e) {
        return ResponseEntity.badRequest().body(new ExpertErrorResponse(e.getMessage(), "VALIDATION", e.getFields()));
    }

    @ExceptionHandler(AssetAffectationNotFoundException.class)
    public ResponseEntity<ExpertErrorResponse> affectationNotFound(AssetAffectationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ExpertErrorResponse(e.getMessage(), "AFFECTATION_NOT_FOUND", null));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ExpertErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ExpertErrorResponse(e.getMessage(), "BAD_REQUEST", null));
    }
}
