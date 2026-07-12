package fr.ses10doigts.tradeIO5.service.tree.media;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Résout la {@link ApiCredentialDTO} YouTube pour le compte de l'utilisateur technique "System" —
 * même principe que {@code MacroCredentialResolver} (une classe dédiée par domaine plutôt qu'un
 * résolveur générique partagé qui coupleraient des modules qui n'ont pas à se connaître).
 */
@Service
public class MediaCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(MediaCredentialResolver.class);

    private static final String SYSTEM_USERNAME = "System";

    private final UserRepository userRepository;
    private final ApiCredentialRepository apiCredentialRepository;

    public MediaCredentialResolver(UserRepository userRepository, ApiCredentialRepository apiCredentialRepository) {
        this.userRepository = userRepository;
        this.apiCredentialRepository = apiCredentialRepository;
    }

    public ApiCredentialDTO resolve(WebProviderCode provider) {
        return userRepository.findByUsername(SYSTEM_USERNAME)
                .flatMap(sysUser -> apiCredentialRepository
                        .findByUserAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(sysUser, provider))
                .map(cred -> new ApiCredentialDTO(
                        provider,
                        cred.getApiKey(),
                        cred.getSecretKey(),
                        cred.getWebProvider().getApiBaseUrl()
                ))
                .orElseGet(() -> {
                    log.warn("Aucune credential '{}' trouvée pour l'utilisateur '{}' : source de veille média indisponible.",
                            provider, SYSTEM_USERNAME);
                    return null;
                });
    }
}
