package fr.ses10doigts.tradeIO5.service.connector.keypair;

import fr.ses10doigts.tradeIO5.model.dto.admin.CredentialStoredDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.KeyPairProviderDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.PendingKeyDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ProviderPendingKey;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderPendingKeyRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.admin.AdminResourceNotFoundException;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialService;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moteur de clés par utilisateur, commun à tous les providers dont l'authentification repose sur une paire de clés que l'utilisateur
 * enregistre chez l'exchange (cf. {@link ProviderKeyPairSupport}).
 * <ol>
 *   <li>{@link #generate} : le serveur crée la paire, chiffre la privée ({@link SecretCipher}), garde la paire en attente et renvoie la clé
 *       publique à enregistrer chez l'exchange ;</li>
 *   <li>{@link #promote} : l'utilisateur saisit la clé API reçue ; le provider la vérifie par un appel réel signé, puis la credential
 *       (READ ou TRADE) est enregistrée par les services existants ({@link ReadCredentialService}, {@link TradeCredentialService}) et la
 *       paire en attente supprimée. Vérification refusée ou impossible : rien n'est promu, la paire reste en attente ;</li>
 *   <li>régénérer = générer à nouveau (remplace la paire en attente ; la credential active n'est touchée qu'à la promotion suivante, donc
 *       aucune coupure) ; {@link #cancel} et {@link #purgeExpired} suppriment les paires non promues.</li>
 * </ol>
 * Aucune clé privée en clair hors de la mémoire du processus, ni dans un DTO, un log, une exception ou le journal d'audit. {@code promote}
 * n'est volontairement pas transactionnel : l'appel réseau de vérification ne doit pas tenir une transaction ouverte.
 */
@Slf4j
@Service
public class ProviderKeyPairService {

    private static final String EVENT_GENERATED = "KEY_GENERATED";
    private static final String EVENT_PROMOTED = "KEY_PROMOTED";
    private static final String EVENT_CANCELLED = "KEY_CANCELLED";

    private final ProviderPendingKeyRepository pending;
    private final ProviderRepository providers;
    private final SecretCipher cipher;
    private final TradeCredentialService tradeCredentials;
    private final ReadCredentialService readCredentials;
    private final AdminActionLogService journal;
    private final DomainClock clock;
    private final Map<WebProviderCode, ProviderKeyPairSupport> supports = new EnumMap<>(WebProviderCode.class);

    public ProviderKeyPairService(ProviderPendingKeyRepository pending, ProviderRepository providers, SecretCipher cipher,
                                  TradeCredentialService tradeCredentials, ReadCredentialService readCredentials,
                                  AdminActionLogService journal, DomainClock clock, List<ProviderKeyPairSupport> supportList) {
        this.pending = pending;
        this.providers = providers;
        this.cipher = cipher;
        this.tradeCredentials = tradeCredentials;
        this.readCredentials = readCredentials;
        this.journal = journal;
        this.clock = clock;
        supportList.forEach(s -> supports.put(s.provider(), s));
    }

    private record Target(WebProviderCode provider, CredentialScope scope, ProviderKeyPairSupport support) {
    }

    /** {@code true} si le serveur génère les clés de ce provider. */
    public boolean supports(WebProviderCode provider) {
        return supports.containsKey(provider);
    }

    /** Providers pour lesquels une paire peut être générée. */
    public List<KeyPairProviderDto> supported() {
        return supports.values().stream().sorted(Comparator.comparing(s -> s.provider().name()))
                .map(s -> new KeyPairProviderDto(s.provider().name(), s.scopes().stream().map(Enum::name).sorted().toList(),
                        s.algorithm(), s.registrationHint())).toList();
    }

    /**
     * Génère (ou régénère) la paire en attente de (utilisateur, provider, portée).
     *
     * @throws IllegalArgumentException provider ou portée non gérés, provider non déclaré
     * @throws MasterKeyMissingException clé maître absente
     */
    @Transactional
    public PendingKeyDto generate(User user, String providerName, String scopeName) {
        Target target = target(providerName, scopeName);
        requireMasterKey();
        providerOf(target.provider());
        ProviderKeyPairSupport support = target.support();
        KeyPair pair;
        String fingerprint;
        try {
            pair = PemKeys.generate(support.algorithm(), support.keySize());
            fingerprint = PemKeys.fingerprint(pair);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Génération de la paire de clés impossible (" + support.algorithm() + ")", e);
        }
        LocalDateTime now = now();
        ProviderPendingKey entity = pending.findByUserAndProviderCodeAndScope(user, target.provider().name(), target.scope())
                .orElseGet(() -> ProviderPendingKey.builder().user(user).providerCode(target.provider().name()).scope(target.scope())
                        .build());
        entity.setAlgorithm(support.algorithm());
        entity.setPrivateKeyEnc(cipher.encrypt(PemKeys.privatePem(pair), context(user, target)));
        entity.setPublicKeyPem(PemKeys.publicPem(pair));
        entity.setFingerprint(fingerprint);
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(KeyPairDefaults.PENDING_TTL));
        ProviderPendingKey saved = pending.save(entity);
        record(user, EVENT_GENERATED, target, fingerprint, AdminActionOutcome.OK);
        return toDto(saved, support);
    }

    /** Paire en attente non expirée (clé publique à (ré)afficher), si elle existe. */
    @Transactional(readOnly = true)
    public Optional<PendingKeyDto> pending(User user, String providerName, String scopeName) {
        Target target = target(providerName, scopeName);
        LocalDateTime now = now();
        return pending.findByUserAndProviderCodeAndScope(user, target.provider().name(), target.scope())
                .filter(p -> p.getExpiresAt().isAfter(now)).map(p -> toDto(p, target.support()));
    }

    /**
     * Vérifie la clé API auprès de l'exchange avec la clé privée de la paire en attente, puis enregistre la credential.
     *
     * @throws AdminResourceNotFoundException aucune paire en attente
     * @throws IllegalArgumentException clé API vide, paire expirée, clé refusée par l'exchange
     * @throws KeyVerificationUnavailableException exchange injoignable : rien n'est promu
     * @throws MasterKeyMissingException clé maître absente
     */
    public CredentialStoredDto promote(User user, String providerName, String scopeName, String apiKey) {
        Target target = target(providerName, scopeName);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey est requise");
        }
        requireMasterKey();
        ProviderPendingKey pendingKey = pending.findByUserAndProviderCodeAndScope(user, target.provider().name(), target.scope())
                .orElseThrow(() -> new AdminResourceNotFoundException("Aucune paire en attente pour " + target.provider() + " / "
                        + target.scope() + " : générez-en une d'abord"));
        if (!pendingKey.getExpiresAt().isAfter(now())) {
            pending.delete(pendingKey);
            throw new IllegalArgumentException("La paire en attente a expiré : générez-en une nouvelle");
        }
        WebProvider webProvider = providerOf(target.provider());
        String privatePem = cipher.decrypt(pendingKey.getPrivateKeyEnc(), context(user, target));
        ProviderKeyPairSupport.CredentialFields fields = target.support().fields(apiKey.strip(), privatePem);
        verify(user, target, webProvider, fields, pendingKey.getFingerprint());
        ApiCredential saved = target.scope() == CredentialScope.TRADE
                ? tradeCredentials.store(user, target.provider(), fields.apiKey(), fields.secretKey(), fields.passphrase(), true)
                : readCredentials.store(user, target.provider(), fields.apiKey(), fields.secretKey(), fields.passphrase(), true);
        pending.delete(pendingKey);
        record(user, EVENT_PROMOTED, target, pendingKey.getFingerprint(), AdminActionOutcome.OK);
        return new CredentialStoredDto(saved.getId(), target.provider().name(), target.scope().name(), saved.isEnabled());
    }

    /** Supprime la paire en attente (idempotent). */
    @Transactional
    public void cancel(User user, String providerName, String scopeName) {
        Target target = target(providerName, scopeName);
        pending.findByUserAndProviderCodeAndScope(user, target.provider().name(), target.scope()).ifPresent(p -> {
            pending.delete(p);
            record(user, EVENT_CANCELLED, target, p.getFingerprint(), AdminActionOutcome.OK);
        });
    }

    /** Supprime les paires expirées. @return nombre de paires supprimées */
    @Transactional
    public long purgeExpired() {
        long purged = pending.deleteByExpiresAtBefore(now());
        if (purged > 0) {
            log.info("Paires de clés en attente expirées purgées : {}", purged);
        }
        return purged;
    }

    private void verify(User user, Target target, WebProvider webProvider, ProviderKeyPairSupport.CredentialFields fields,
                        String fingerprint) {
        ApiCredential candidate = ApiCredential.builder().webProvider(webProvider).scope(target.scope()).apiKey(fields.apiKey())
                .secretKey(fields.secretKey()).passphrase(fields.passphrase()).enabled(true).build();
        try {
            target.support().verify(candidate);
        } catch (CredentialRejectedException e) {
            record(user, "KEY_REFUSED", target, fingerprint, AdminActionOutcome.REFUSED);
            throw new IllegalArgumentException("Clé refusée par " + target.provider() + " : " + e.getMessage());
        } catch (BalanceUnavailableException e) {
            record(user, "KEY_UNVERIFIED", target, fingerprint, AdminActionOutcome.ERROR);
            throw new KeyVerificationUnavailableException("Vérification impossible auprès de " + target.provider() + " : " + e.getMessage());
        }
    }

    private Target target(String providerName, String scopeName) {
        WebProviderCode provider;
        CredentialScope scope;
        try {
            provider = WebProviderCode.valueOf(providerName.strip().toUpperCase());
            scope = CredentialScope.valueOf(scopeName.strip().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Provider ou portée inconnus : " + providerName + " / " + scopeName);
        }
        ProviderKeyPairSupport support = supports.get(provider);
        if (support == null) {
            throw new IllegalArgumentException("Aucune génération de clés pour " + provider);
        }
        if (!support.scopes().contains(scope)) {
            throw new IllegalArgumentException("Portée " + scope + " non gérée pour " + provider);
        }
        return new Target(provider, scope, support);
    }

    private WebProvider providerOf(WebProviderCode code) {
        return providers.findByCode(code).orElseThrow(() -> new IllegalArgumentException("Provider non déclaré : " + code
                + " (le déclarer d'abord dans l'onglet Providers)"));
    }

    private void requireMasterKey() {
        if (!cipher.isConfigured()) {
            throw new MasterKeyMissingException();
        }
    }

    /** Contexte authentifié (AAD) : une valeur recopiée sur une autre ligne (autre utilisateur, provider ou portée) ne se déchiffre pas. */
    private static String context(User user, Target target) {
        return "pendingKey:" + user.getId() + ":" + target.provider() + ":" + target.scope();
    }

    private void record(User user, String event, Target target, String fingerprint, AdminActionOutcome outcome) {
        journal.record(user.getUsername(), AdminAction.CREDENTIAL_STORED, "pending-key:" + target.provider() + "/" + target.scope(),
                AdminActionLogService.payload("event", event, "userId", user.getId(), "provider", target.provider(), "scope",
                        target.scope(), "fingerprint", fingerprint), outcome);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
    }

    private static PendingKeyDto toDto(ProviderPendingKey key, ProviderKeyPairSupport support) {
        return new PendingKeyDto(key.getProviderCode(), key.getScope().name(), key.getAlgorithm(), key.getPublicKeyPem(),
                key.getFingerprint(), key.getCreatedAt(), key.getExpiresAt(), support.registrationHint());
    }
}
