package fr.ses10doigts.tradeIO5.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.model.payload.request.LoginRequest;
import fr.ses10doigts.tradeIO5.security.model.payload.request.SignupRequest;
import fr.ses10doigts.tradeIO5.security.repository.RoleRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;

@CrossOrigin(origins = "*", maxAge = 3600)
@Controller
public class MainController {
    @Autowired
    private IAuthenticationFacade authenticationFacade;

    @Autowired
	private RoleRepository roleRepo;
    @Autowired
    private UserPreferencesService preferencesService;

    private static final Logger	  logger = LoggerFactory.getLogger(MainController.class);

    @GetMapping("/")
	public String home(Model model, @RequestParam(required = false, name = "error") Boolean error) {
		User user = null;

		LoginRequest loginRequest = new LoginRequest();
		model.addAttribute("loginRequest", loginRequest);
		model.addAttribute("error", error != null && error);

		try {
			user = authenticationFacade.getConnectedUser();
			if (user != null) {
				// Utilisateur connecté : l'application est sur /app (l'ancienne page d'accueil n'est servie qu'aux visiteurs).
				return "redirect:/app";
			}
			model.addAttribute("loggued", false);
			model.addAttribute("username", "");
		} catch (RuntimeException e) {
			model.addAttribute("loggued", false);
			model.addAttribute("username", "");
		}

		model.addAttribute("page", "home");

		return "home";
    }


    @GetMapping("/login")
    public String login(Model model, @RequestParam(required = false, name = "error") Boolean error) {
		LoginRequest loginRequest = new LoginRequest();

		model.addAttribute("loginRequest", loginRequest);
		model.addAttribute("error", error != null && error);

		model.addAttribute("page", "login");

		return "login";
    }


    @GetMapping("/register")
	public String register(Model model) {
		SignupRequest signUpRequest = new SignupRequest();

		List<Role> roles = roleRepo.findAll();

		model.addAttribute("signUpRequest", signUpRequest);
		model.addAttribute("allRoles", roles);

		model.addAttribute("page", "register");

		return "register";
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
