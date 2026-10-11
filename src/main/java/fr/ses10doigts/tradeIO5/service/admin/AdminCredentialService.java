package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminCredentialDto;
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
