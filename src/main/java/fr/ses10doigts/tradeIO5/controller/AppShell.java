package fr.ses10doigts.tradeIO5.controller;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;

import fr.ses10doigts.tradeIO5.model.entity.user.DisplayMode;
import fr.ses10doigts.tradeIO5.model.entity.user.UiTheme;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Preferences;

/**
 * Attributs communs de la coquille de la charte (thème, mode d'affichage, identité, navigation) posés sur toute page qui l'utilise :
 * pages {@code /app/**}, {@code /admin} et {@code /info}. Préférences absentes ⇒ sombre et mode simple.
 */
final class AppShell {

    private AppShell() {
    }

    static void populate(Model model, Authentication auth, Preferences prefs, String page, String title) {
        String name = auth.getName();
        model.addAttribute("page", page);
        model.addAttribute("title", title);
        model.addAttribute("username", name);
        model.addAttribute("initial", name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        model.addAttribute("admin", auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
        model.addAttribute("theme", prefs != null && prefs.theme() == UiTheme.LIGHT ? "light" : "dark");
        DisplayMode mode = prefs == null || prefs.displayMode() == null ? DisplayMode.SIMPLE : prefs.displayMode();
        model.addAttribute("displayMode", mode.name().toLowerCase());
    }
}
