package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.controller.RainbowLiveControllerAdvice.ErrorResponse;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.RainbowBandsDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException;
import fr.ses10doigts.tradeIO5.service.portfolio.PortfolioPeriod;
import fr.ses10doigts.tradeIO5.service.portfolio.RainbowBandsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bandes Rainbow figées d'un lien de l'utilisateur (lecture base). Période invalide ⇒ 400 ; lien d'autrui ou inconnu ⇒ 404. */
@RestController
@RequestMapping("/api/rainbow-live/bindings/{id}/bands")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveBandsController {

    private final RainbowBandsQueryService service;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public RainbowBandsDto bands(@PathVariable Long id, @RequestParam(defaultValue = "90d") String period) {
        return service.bands(authenticationFacade.getConnectedUser(), id, PortfolioPeriod.of(period));
    }

    @ExceptionHandler(RainbowLiveBindingNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(RainbowLiveBindingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }
}
