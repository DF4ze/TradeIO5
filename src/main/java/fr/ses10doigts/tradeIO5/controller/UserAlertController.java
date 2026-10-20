package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.AlertDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Alertes de l'utilisateur connecté (dérivées des statuts en base, types coupés exclus). Liste vide = rien à signaler. */
@RestController
@RequestMapping("/api/user/alerts")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class UserAlertController {

    private final UserAlertService service;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public List<AlertDto> alerts() {
        return service.alerts(authenticationFacade.getConnectedUser());
    }
}
