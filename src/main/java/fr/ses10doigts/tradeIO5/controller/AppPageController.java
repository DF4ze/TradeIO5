package fr.ses10doigts.tradeIO5.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Preferences;
import lombok.RequiredArgsConstructor;

/**
 * Pages de l'application utilisateur ({@code /app/**}) : coquille Thymeleaf servie à tout utilisateur connecté ; les données
 * viennent des endpoints {@code /api/**} chargés côté navigateur (jamais de donnée de trading dans le rendu serveur). Le thème
 * et le mode d'affichage (préférences) sont posés dans la page pour éviter tout clignotement. {@code ?demo=1} (traité côté
 * navigateur) remplace les appels API par un jeu de données fictif en lecture seule.
 */
@Controller
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class AppPageController {

    private final IAuthenticationFacade authenticationFacade;
    private final UserPreferencesService preferencesService;

    @GetMapping("/app")
    public String home(Model model) {
        return page(model, "home", "Accueil");
    }

    @GetMapping("/app/assets")
    public String assets(Model model) {
        return page(model, "assets", "Actifs gérés");
    }

    @GetMapping("/app/assets/{symbol:[A-Za-z0-9._-]{1,20}}")
    public String asset(@PathVariable String symbol, Model model) {
        model.addAttribute("symbol", symbol.toUpperCase());
        return page(model, "asset", symbol.toUpperCase());
    }

    @GetMapping("/app/journal")
    public String journal(Model model) {
        return page(model, "journal", "Journal");
    }

    @GetMapping("/app/settings")
    public String settings(Model model) {
        return page(model, "settings", "Réglages");
    }

    @GetMapping("/app/onboarding")
    public String onboarding(Model model) {
        return page(model, "onboarding", "Démarrage");
    }

    /** Catalogue des composants communs (page de démonstration, aucune donnée réelle). */
    @GetMapping("/app/components")
    public String components(Model model) {
        return page(model, "components", "Composants");
    }

    private String page(Model model, String page, String title) {
        Preferences prefs = preferencesService.get(authenticationFacade.getConnectedUser());
        AppShell.populate(model, authenticationFacade.getAuthentication(), prefs, page, title);
        return "app/" + page;
    }
}
