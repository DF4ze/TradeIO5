package fr.ses10doigts.tradeIO5.service.execution.credential;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Seul point de lecture des credentials {@link CredentialScope#TRADE} : résolution par (utilisateur, provider, portée),
 * déchiffrement en mémoire, retour d'une <b>copie détachée</b> (jamais l'entité gérée : le clair ne peut pas être
 * flushé en base). Absente, désactivée, clé maître manquante, valeur non chiffrée ou altérée => {@link Optional#empty()}
 * (l'exécuteur répond {@code CREDENTIAL_INVALID}) ; jamais d'exception brute, jamais de secret loggué.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeCredentialResolver {

    private final ApiCredentialRepository repository;
    private final SecretCipher cipher;

    public Optional<ApiCredential> resolve(User user, WebProviderCode provider) {
        Optional<ApiCredential> stored = repository
                .findByUserAndScopeAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(user, CredentialScope.TRADE, provider);
        if (stored.isEmpty()) {
            log.debug("Credential TRADE absente ou désactivée user={} provider={}", user.getId(), provider);
            return Optional.empty();
        }
        ApiCredential credential = stored.get();
        try {
            return Optional.of(ApiCredential.builder()
                    .id(credential.getId())
                    .user(credential.getUser())
                    .webProvider(credential.getWebProvider())
                    .scope(CredentialScope.TRADE)
                    .apiKey(cipher.decrypt(credential.getApiKey(), "apiKey"))
                    .secretKey(cipher.decrypt(credential.getSecretKey(), "secretKey"))
                    .passphrase(credential.getPassphrase() == null ? null : cipher.decrypt(credential.getPassphrase(), "passphrase"))
                    .enabled(true)
                    .createdAt(credential.getCreatedAt())
                    .build());
        } catch (MasterKeyMissingException | SecretCipherException e) {
            log.warn("Credential TRADE illisible user={} provider={} : {}", user.getId(), provider, e.getMessage());
            return Optional.empty();
        }
    }
}
