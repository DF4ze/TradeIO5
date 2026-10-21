package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Saisie par l'utilisateur de sa clé de LECTURE (soldes, catalogue, frais) sur un exchange : stockée chiffrée
 * ({@link ReadSecretConverter}), jamais renvoyée. Crée (ou rattache) le wallet de l'exchange. Refusée sans clé maître : aucune clé
 * n'est jamais écrite en clair par cette voie.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadCredentialService {

    private final ApiCredentialRepository repository;
    private final ProviderRepository providerRepository;
    private final WalletRepository walletRepository;
    private final DomainClock clock;

    /**
     * Crée ou remplace (rotation) la clé READ de (user, provider).
     *
     * @throws IllegalArgumentException provider non géré, champ manquant
     * @throws MasterKeyMissingException clé maître absente
     */
    @Transactional
    public ApiCredential store(User user, WebProviderCode provider, String apiKey, String secretKey, String passphrase,
                               boolean enabled) {
        if (provider == null || !TradeCredentialService.TRADABLE_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("Exchange non géré pour la lecture : " + provider);
        }
        boolean withPassphrase = TradeCredentialService.PASSPHRASE_PROVIDERS.contains(provider);
        if (isBlank(apiKey) || isBlank(secretKey) || (withPassphrase && isBlank(passphrase))) {
            throw new IllegalArgumentException(withPassphrase ? "apiKey, secretKey et passphrase sont requis pour " + provider
                    : "apiKey et secretKey sont requis pour " + provider + " (pas de passphrase)");
        }
        if (!ReadSecretConverter.active()) {
            throw new MasterKeyMissingException();
        }
        WebProvider webProvider = providerRepository.findByCode(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider inconnu : " + provider));
        ApiCredential credential = repository.findByUserAndWebProviderAndScope(user, webProvider, CredentialScope.READ)
                .orElseGet(() -> ApiCredential.builder().user(user).webProvider(webProvider).scope(CredentialScope.READ)
                        .createdAt(LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).build());
        credential.setApiKey(apiKey.strip());
        credential.setSecretKey(secretKey.strip());
        credential.setPassphrase(withPassphrase ? passphrase.strip() : null);
        credential.setEnabled(enabled);
        ApiCredential saved = repository.save(credential);
        attachWallet(user, webProvider, saved);
        log.info("Credential READ enregistrée id={} user={} provider={} enabled={}", saved.getId(), user.getId(), provider, enabled);
        return saved;
    }

    private void attachWallet(User user, WebProvider webProvider, ApiCredential credential) {
        String name = webProvider.getCode().name();
        Wallet wallet = walletRepository.findByUserAndName(user, name).orElse(null);
        if (wallet == null) {
            walletRepository.save(Wallet.builder().name(name).enabled(true).source(WalletSource.EXCHANGE)
                    .webProviderCode(webProvider.getCode()).description("Compte " + name + " (lecture seule)")
                    .webProvider(webProvider).user(user).creationDate(LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC))
                    .credential(credential).build());
        } else if (wallet.getCredential() == null || !wallet.getCredential().getId().equals(credential.getId())) {
            wallet.setCredential(credential);
            walletRepository.save(wallet);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
