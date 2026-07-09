package fr.ses10doigts.tradeIO5.service.tree.macro;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Résout la {@link ApiCredentialDTO} pour un {@link WebProviderCode} donné, pour le compte de
 * l'utilisateur technique "System" — même principe que {@code IndicatorCredentialResolver}, mais
 * keyé directement par {@link WebProviderCode} plutôt que par {@code IndicatorType} : le calendrier
 * macro (étude "indicateurs-macro-externes" §14 item G) n'est pas un {@code Indicator}, donc
 * {@code IndicatorCredentialResolver} (qui résout par {@code IndicatorType}) ne s'applique pas ici
 * tel quel (choix documenté dans le prompt d'implémentation, item G, point 5 : "à étendre... à
 * trancher" — extension retenue : une classe dédiée plutôt que modifier
 * {@code IndicatorCredentialResolver}, pour ne pas coupler un résolveur d'indicateurs à un besoin
 * qui n'en est pas un).
 */
@Service
public class MacroCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(MacroCredentialResolver.class);

    private static final String SYSTEM_USERNAME = "System";

    private final UserRepository userRepository;
    private final ApiCredentialRepository apiCredentialRepository;

    public MacroCredentialResolver(UserRepository userRepository, ApiCredentialRepository apiCredentialRepository) {
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
                    log.warn("Aucune credential '{}' trouvée pour l'utilisateur '{}' : source calendrier macro indisponible.",
                            provider, SYSTEM_USERNAME);
                    return null;
                });
    }
}
