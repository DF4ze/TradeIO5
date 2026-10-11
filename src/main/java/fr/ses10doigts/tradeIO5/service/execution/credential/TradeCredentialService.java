package fr.ses10doigts.tradeIO5.service.execution.credential;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * Création / remplacement d'une credential TRADE (API admin uniquement). Les secrets (clé, secret et, chez OKX, passphrase) sont chiffrés avant d'être
 * écrits ; sans clé maître l'opération échoue ({@link MasterKeyMissingException}), rien n'est écrit en clair.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeCredentialService {

    /** Exchanges disposant d'un client d'ordres. */
    public static final Set<WebProviderCode> TRADABLE_PROVIDERS = Set.of(WebProviderCode.OKX, WebProviderCode.KRAKEN);

    /** Exchanges dont la clé Trade porte une passphrase (OKX) ; chez les autres (Kraken) elle est ignorée, jamais stockée. */
    public static final Set<WebProviderCode> PASSPHRASE_PROVIDERS = Set.of(WebProviderCode.OKX);

    private final ApiCredentialRepository repository;
    private final ProviderRepository providerRepository;
    private final SecretCipher cipher;
    private final DomainClock clock;

    /**
     * Crée ou remplace (rotation) la credential TRADE de (user, provider).
     *
     * @throws IllegalArgumentException provider sans client d'ordres, champ manquant
     * @throws MasterKeyMissingException clé maître absente
     */
    @Transactional
    public ApiCredential store(User user, WebProviderCode provider, String apiKey, String secretKey, String passphrase,
                               boolean enabled) {
        if (!TRADABLE_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Aucun client d'ordres pour " + provider);
        }
        boolean withPassphrase = PASSPHRASE_PROVIDERS.contains(provider);
        if (isBlank(apiKey) || isBlank(secretKey) || (withPassphrase && isBlank(passphrase))) {
            throw new IllegalArgumentException(withPassphrase ? "apiKey, secretKey et passphrase sont requis pour " + provider
                    : "apiKey et secretKey sont requis pour " + provider + " (pas de passphrase)");
        }
        if (!cipher.isConfigured()) {
            throw new MasterKeyMissingException();
        }
        WebProvider webProvider = providerRepository.findByCode(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider inconnu : " + provider));
        ApiCredential credential = repository.findByUserAndWebProviderAndScope(user, webProvider, CredentialScope.TRADE)
                .orElseGet(() -> ApiCredential.builder().user(user).webProvider(webProvider).scope(CredentialScope.TRADE)
                        .createdAt(LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).build());
        credential.setApiKey(cipher.encrypt(apiKey, "apiKey"));
        credential.setSecretKey(cipher.encrypt(secretKey, "secretKey"));
        credential.setPassphrase(withPassphrase ? cipher.encrypt(passphrase, "passphrase") : null);
        credential.setEnabled(enabled);
        ApiCredential saved = repository.save(credential);
        log.info("Credential TRADE enregistrée id={} user={} provider={} enabled={}", saved.getId(), user.getId(), provider, enabled);
        return saved;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
