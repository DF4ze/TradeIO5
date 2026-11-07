package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.entity.user.DisplayMode;
import fr.ses10doigts.tradeIO5.security.model.ERole;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.preferences.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Garde de l'Espace Expert (API et futurs écrans) : exige un utilisateur authentifié dont le {@code displayMode} est EXPERT
 * (l'ADMIN y est soumis comme les autres), sinon 403 {@code EXPERT_MODE_REQUIRED}. Appelée en tête de chaque endpoint.
 */
@Component
@RequiredArgsConstructor
public class ExpertAccessGuard {

    private final IAuthenticationFacade authenticationFacade;
    private final UserPreferencesService preferences;

    public ExpertContext require() {
        User user = authenticationFacade.getConnectedUser();
        if (preferences.get(user).displayMode() != DisplayMode.EXPERT) {
            throw new ExpertApiException(ExpertApiException.Code.EXPERT_MODE_REQUIRED,
                    "L'Espace Expert exige le mode d'affichage Expert (réglage utilisateur)");
        }
        return new ExpertContext(user, isAdmin(authenticationFacade.getAuthentication()));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ERole.ROLE_ADMIN.name().equals(a.getAuthority()));
    }
}
