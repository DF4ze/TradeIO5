package fr.ses10doigts.tradeIO5.controller;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;

/**
 * Lecture/écriture du curseur de risque continu (0-10) de l'utilisateur connecté. Patron exact
 * d'{@link AssetOverviewController} : résolution de l'utilisateur via {@link IAuthenticationFacade}.
 */
@RestController
@RequestMapping("/api/user/risk-cursor")
@PreAuthorize("isAuthenticated()")
public class UserTradingSettingsController {

    private final UserTradingSettingsService userTradingSettingsService;
    private final IAuthenticationFacade authenticationFacade;

    public UserTradingSettingsController(UserTradingSettingsService userTradingSettingsService,
                                          IAuthenticationFacade authenticationFacade) {
        this.userTradingSettingsService = userTradingSettingsService;
        this.authenticationFacade = authenticationFacade;
    }

    @GetMapping
    public Map<String, Integer> getRiskCursor() {
        User user = authenticationFacade.getConnectedUser();
        return Map.of("riskCursor", userTradingSettingsService.getRiskCursor(user));
    }

    @PutMapping
    public Map<String, Integer> setRiskCursor(@RequestParam int riskCursor) {
        User user = authenticationFacade.getConnectedUser();
        userTradingSettingsService.setRiskCursor(user, riskCursor);
        return Map.of("riskCursor", riskCursor);
    }
}
