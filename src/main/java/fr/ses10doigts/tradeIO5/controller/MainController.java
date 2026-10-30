package fr.ses10doigts.tradeIO5.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.model.payload.request.LoginRequest;
import fr.ses10doigts.tradeIO5.security.model.payload.request.SignupRequest;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;

@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
public class MainController {
    @Autowired
    private IAuthenticationFacade authenticationFacade;

    @Autowired
    private UserPreferencesService preferencesService;

    private static final Logger	  logger = LoggerFactory.getLogger(MainController.class);

    /** Page de présentation publique ; un utilisateur connecté est envoyé sur l'application ({@code /app}). */
    @GetMapping("/")
    public String home() {
		return isConnected() ? "redirect:/app" : "home";
    }


    @GetMapping("/login")
    public String login(Model model, @RequestParam(required = false, name = "error") Boolean error) {
		if (isConnected()) {
			return "redirect:/app";
		}
		model.addAttribute("loginRequest", new LoginRequest());
		model.addAttribute("error", error != null && error);
		return "login";
    }


    /**
     * Formulaire d'inscription. Le retour de {@code AuthController} arrive en paramètres de redirection :
     * {@code ok}, {@code exception} et les messages de validation ({@code signUpRequestN}).
     */
    @GetMapping("/register")
    public String register(Model model, @RequestParam Map<String, String> params) {
		if (isConnected()) {
			return "redirect:/app";
		}
		model.addAttribute("signUpRequest", new SignupRequest());
		model.addAttribute("ok", params.containsKey("ok"));
		model.addAttribute("exception", params.get("exception"));
		model.addAttribute("errors", params.entrySet().stream().filter(e -> e.getKey().startsWith("signUpRequest")).map(Map.Entry::getValue).toList());
		return "register";
    }


    private boolean isConnected() {
		try {
			return authenticationFacade.getConnectedUser() != null;
		} catch (RuntimeException e) {
			return false;
		}
    }


    @GetMapping("/user")
    @PreAuthorize("hasRole('USER') or hasRole('MODERATOR') or hasRole('ADMIN')")
    public String userAccess(Model model) {
		model.addAttribute("name", authenticationFacade.getAuthentication().getName());
		model.addAttribute("loggued", true);

		model.addAttribute("page", "user");

		return "userPage";
    }


    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminAccess(Model model) {
		model.addAttribute("name", authenticationFacade.getAuthentication().getName());
		model.addAttribute("loggued", true);
		shell(model, "admin", "Administration");

		return "adminPage";
    }

    /** Procédure d'un achat réel de test depuis l'interface (texte statique, aucun appel exchange). */
    @GetMapping("/info")
    @PreAuthorize("hasRole('ADMIN')")
    public String infoAccess(Model model) {
		model.addAttribute("name", authenticationFacade.getAuthentication().getName());
		model.addAttribute("loggued", true);
		shell(model, "info", "Info");

		return "infoPage";
    }

    /** Attributs de la coquille de la charte (thème, mode, identité) ; préférences illisibles ⇒ valeurs par défaut. */
    private void shell(Model model, String page, String title) {
		fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService.Preferences prefs = null;
		try {
			prefs = preferencesService.get(authenticationFacade.getConnectedUser());
		} catch (RuntimeException e) {
			logger.warn("Préférences illisibles pour la page {} : valeurs par défaut", page);
		}
		AppShell.populate(model, authenticationFacade.getAuthentication(), prefs, page, title);
    }
}
