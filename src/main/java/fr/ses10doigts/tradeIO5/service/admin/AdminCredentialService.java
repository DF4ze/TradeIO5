package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.controller.AdminCredentialController.StoreRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminCredentialDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadSecretConverter;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.credential.MasterKeyMissingException;
import fr.ses10doigts.tradeIO5.service.execution.credential.TradeCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Supervision et saisie des credentials d'exchange. Le propriétaire saisit ses clés TRADE ; l'admin liste (masqué) et
 * (dés)active. Aucun secret en retour, en log ni au journal.
 */
@Service
@RequiredArgsConstructor
public class AdminCredentialService {

    /** En dessous de cette longueur, aucun fragment de la clé n'est montré. */
    private static final int HINT_MIN_KEY_LENGTH = 8;
    private static final int HINT_LENGTH = 4;

    private final ApiCredentialRepository repository;
    private final UserRepository userRepository;
    private final TradeCredentialService tradeCredentials;
    private final ReadCredentialService readCredentials;
    private final ProviderRepository providerRepository;
    private final AdminActionLogService journal;

    @Transactional(readOnly = true)
    public List<AdminCredentialDto> list() {
        return repository.findAll().stream().sorted(Comparator.comparing((ApiCredential c) -> c.getUser().getId())
                        .thenComparing(c -> c.getWebProvider().getCode()).thenComparing(ApiCredential::getScope))
                .map(AdminCredentialService::toDto).toList();
    }

    @Transactional
    public AdminCredentialDto setEnabled(Long id, boolean enabled, String actor) {
        String target = "credential:" + id;
        ApiCredential credential = repository.findById(id).orElse(null);
        if (credential == null) {
            journal.record(actor, AdminAction.CREDENTIAL_ENABLED, target, null, AdminActionOutcome.REFUSED);
            throw new AdminResourceNotFoundException("Credential introuvable : " + id);
        }
        credential.setEnabled(enabled);
        AdminCredentialDto dto = toDto(repository.save(credential));
        journal.record(actor, AdminAction.CREDENTIAL_ENABLED, target, AdminActionLogService.payload("enabled", enabled,
                "provider", dto.provider(), "scope", dto.scope(), "userId", dto.userId()), AdminActionOutcome.OK);
        return dto;
    }

    /**
     * Saisie par l'admin pour {@code request.userId()} (n'importe quel compte, System compris). Portée {@code TRADE} : clé d'ordres
     * (exchange avec client d'ordres, chiffrée). Portée {@code READ} : exchange géré en lecture (avec wallet rattaché) ou, pour un
     * provider de données (COINALYZE, TWELVE_DATA…), clé simple sans secret obligatoire. Journal {@code CREDENTIAL_STORED}, sans secret.
     */
    @Transactional
    public AdminCredentialDto storeAs(StoreRequest request, String actor) {
        String attempted = request == null ? "credential" : "user:" + request.userId() + "/" + request.provider();
        try {
            if (request == null || request.userId() == null || request.provider() == null || request.provider().isBlank()) {
                throw new IllegalArgumentException("userId et provider sont requis");
            }
            User user = userRepository.findById(request.userId())
                    .orElseThrow(() -> new IllegalArgumentException("Utilisateur inconnu : " + request.userId()));
            WebProviderCode provider;
            try {
                provider = WebProviderCode.valueOf(request.provider().strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Exchange / provider inconnu : " + request.provider());
            }
            CredentialScope scope = request.scope() == null || request.scope().isBlank() ? CredentialScope.TRADE
                    : CredentialScope.valueOf(request.scope().strip().toUpperCase());
            boolean on = request.enabled() == null || request.enabled();
            ApiCredential saved;
            if (scope == CredentialScope.TRADE) {
                saved = store(user, actor, provider, request.apiKey(), request.secretKey(), request.passphrase(), on);
                return toDto(saved);
            }
            if (TradeCredentialService.TRADABLE_PROVIDERS.contains(provider)) {
                saved = readCredentials.store(user, provider, request.apiKey(), request.secretKey(), request.passphrase(), on);
            } else {
                saved = storeDataProviderKey(user, provider, request.apiKey(), request.secretKey(), on);
            }
            journal.record(actor, AdminAction.CREDENTIAL_STORED, "credential:" + saved.getId(), AdminActionLogService.payload(
                    "userId", user.getId(), "provider", provider, "scope", CredentialScope.READ, "enabled", on), AdminActionOutcome.OK);
            return toDto(saved);
        } catch (IllegalArgumentException | MasterKeyMissingException e) {
            if (request == null || request.scope() == null || !"TRADE".equalsIgnoreCase(request.scope())) {
                journal.record(actor, AdminAction.CREDENTIAL_STORED, attempted, AdminActionLogService.payload("reason",
                        e.getClass().getSimpleName()), AdminActionOutcome.REFUSED);
            }
            throw e;
        }
    }

    /** Clé d'un provider de données (pas un exchange d'ordres) : une clé suffit, secret facultatif ; ni wallet ni chiffrement spécifique. */
    private ApiCredential storeDataProviderKey(User user, WebProviderCode provider, String apiKey, String secretKey, boolean enabled) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey est requise pour " + provider);
        }
        if (ReadSecretConverter.blocksWrites()) {
            throw new MasterKeyMissingException();
        }
        WebProvider webProvider = providerRepository.findByCode(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider non déclaré : " + provider + " (le déclarer d'abord dans l'onglet Providers)"));
        ApiCredential credential = repository.findByUserAndWebProviderAndScope(user, webProvider, CredentialScope.READ)
                .orElseGet(() -> ApiCredential.builder().user(user).webProvider(webProvider).scope(CredentialScope.READ)
                        .createdAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).build());
        credential.setApiKey(apiKey.strip());
        credential.setSecretKey(secretKey == null || secretKey.isBlank() ? null : secretKey.strip());
        credential.setEnabled(enabled);
        return repository.save(credential);
    }

    /** Endpoint admin historique : la clé est stockée pour l'utilisateur {@code userId}. */
    public ApiCredential storeFor(Long userId, String actor, WebProviderCode provider, String apiKey, String secretKey,
                                  String passphrase, Boolean enabled) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null) {
            journal.record(actor, AdminAction.CREDENTIAL_STORED, "user:" + userId, AdminActionLogService.payload("provider",
                    provider), AdminActionOutcome.REFUSED);
            throw new IllegalArgumentException("Utilisateur inconnu : " + userId);
        }
        return store(user, actor, provider, apiKey, secretKey, passphrase, enabled);
    }

    /** Crée ou remplace (rotation) la clé TRADE de {@code user}. */
    public ApiCredential store(User user, String actor, WebProviderCode provider, String apiKey, String secretKey,
                               String passphrase, Boolean enabled) {
        boolean on = enabled == null || enabled;
        String attempted = "user:" + user.getId() + "/" + provider;
        try {
            if (provider == null) {
                throw new IllegalArgumentException("provider est requis");
            }
            ApiCredential saved = tradeCredentials.store(user, provider, apiKey, secretKey, passphrase, on);
            journal.record(actor, AdminAction.CREDENTIAL_STORED, "credential:" + saved.getId(), AdminActionLogService.payload(
                    "userId", user.getId(), "provider", provider, "scope", CredentialScope.TRADE, "enabled", on),
                    AdminActionOutcome.OK);
            return saved;
        } catch (IllegalArgumentException | MasterKeyMissingException e) {
            journal.record(actor, AdminAction.CREDENTIAL_STORED, attempted, AdminActionLogService.payload("reason",
                    e.getClass().getSimpleName()), AdminActionOutcome.REFUSED);
            throw e;
        } catch (RuntimeException e) {
            journal.record(actor, AdminAction.CREDENTIAL_STORED, attempted, AdminActionLogService.payload("error",
                    e.getClass().getSimpleName()), AdminActionOutcome.ERROR);
            throw e;
        }
    }

    static AdminCredentialDto toDto(ApiCredential c) {
        return new AdminCredentialDto(c.getId(), c.getUser().getId(), c.getUser().getUsername(), c.getWebProvider().getCode().name(),
                c.getScope().name(), c.isEnabled(), c.getCreatedAt(), hint(c));
    }

    private static String hint(ApiCredential c) {
        if (c.getScope() == CredentialScope.TRADE) {
            return AdminCredentialDto.ENCRYPTED_HINT;
        }
        String key = c.getApiKey();
        return key == null || key.length() < HINT_MIN_KEY_LENGTH ? null : key.substring(key.length() - HINT_LENGTH);
    }
}
