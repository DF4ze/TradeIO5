package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.controller.RainbowLiveControllerAdvice.ErrorResponse;
import fr.ses10doigts.tradeIO5.model.entity.user.DisplayMode;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.entity.user.UiTheme;
import fr.ses10doigts.tradeIO5.model.enumerate.AlertType;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Preferences;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.ProfileResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Préférences de l'utilisateur connecté : thème, alertes coupées, plafonds de dépense personnels (resserrent les plafonds absolus),
 * profil de départ. {@code PUT} remplace le bloc complet ; valeur invalide ⇒ 400, rien n'est écrit.
 */
@RestController
@RequestMapping("/api/user/preferences")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserPreferencesController {

    private final UserPreferencesService service;
    private final IAuthenticationFacade authenticationFacade;

    /** Corps du PUT : {@code theme} nul = sombre ; {@code displayMode} nul = simple ; {@code maxOrderUsd} / {@code maxDayUsd} nuls = pas de plafond personnel. */
    public record PreferencesRequest(UiTheme theme, DisplayMode displayMode, Set<AlertType> alertsDisabled, Double maxOrderUsd, Double maxDayUsd) {
    }

    public record ProfileRequest(StartingProfile profile) {
    }

    @GetMapping
    public Preferences get() {
        return service.get(authenticationFacade.getConnectedUser());
    }

    @PutMapping
    public Preferences put(@RequestBody PreferencesRequest request) {
        return service.update(authenticationFacade.getConnectedUser(), request.theme(), request.displayMode(), request.alertsDisabled(),
                request.maxOrderUsd(), request.maxDayUsd());
    }

    /** Choisit le profil de départ et pose sa bande d'exposition. */
    @PutMapping("/profile")
    public ProfileResult profile(@RequestBody ProfileRequest request) {
        return service.applyProfile(authenticationFacade.getConnectedUser(), request.profile());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
    }
}
