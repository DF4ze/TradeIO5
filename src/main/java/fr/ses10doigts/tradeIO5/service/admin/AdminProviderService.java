package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminProviderDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Déclaration des providers depuis l'admin : un provider = une ligne {@code provider} + un connecteur du code
 * ({@link WebProviderCode}). L'admin choisit un code encore non déclaré, fixe nom / URL de base / activation.
 * Aucun secret ici ; journal {@code PROVIDER}.
 */
@Service
@RequiredArgsConstructor
public class AdminProviderService {

    private static final int NAME_MAX = 100;
    private static final int URL_MAX = 255;

    private final ProviderRepository repository;
    private final AdminActionLogService journal;

    @Transactional(readOnly = true)
    public AdminProviderDto.Listing list() {
        List<WebProvider> all = repository.findAll();
        Set<WebProviderCode> declared = all.stream().map(WebProvider::getCode).collect(Collectors.toSet());
        List<AdminProviderDto> providers = all.stream().sorted(Comparator.comparing(WebProvider::getCode))
                .map(AdminProviderService::toDto).toList();
        List<String> available = Arrays.stream(WebProviderCode.values()).filter(c -> !declared.contains(c))
                .map(Enum::name).toList();
        return new AdminProviderDto.Listing(providers, available);
    }

    @Transactional
    public AdminProviderDto create(String code, String name, String apiBaseUrl, Boolean enabled, String actor) {
        String target = "provider:" + code;
        try {
            WebProviderCode parsed = parseCode(code);
            if (repository.findByCode(parsed).isPresent()) {
                throw new IllegalArgumentException("Provider déjà déclaré : " + parsed);
            }
            WebProvider saved = repository.save(WebProvider.builder().code(parsed).name(cleanName(name))
                    .apiBaseUrl(cleanUrl(apiBaseUrl)).enabled(enabled == null || enabled)
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
            journal.record(actor, AdminAction.PROVIDER, "provider:" + saved.getId(), AdminActionLogService.payload(
                    "op", "create", "code", parsed, "enabled", saved.isEnabled()), AdminActionOutcome.OK);
            return toDto(saved);
        } catch (IllegalArgumentException e) {
            journal.record(actor, AdminAction.PROVIDER, target, AdminActionLogService.payload("op", "create"),
                    AdminActionOutcome.REFUSED);
            throw e;
        }
    }

    @Transactional
    public AdminProviderDto update(Long id, String name, String apiBaseUrl, Boolean enabled, String actor) {
        String target = "provider:" + id;
        WebProvider provider = repository.findById(id).orElse(null);
        if (provider == null) {
            journal.record(actor, AdminAction.PROVIDER, target, AdminActionLogService.payload("op", "update"),
                    AdminActionOutcome.REFUSED);
            throw new AdminResourceNotFoundException("Provider introuvable : " + id);
        }
        try {
            provider.setName(cleanName(name));
            provider.setApiBaseUrl(cleanUrl(apiBaseUrl));
            if (enabled != null) {
                provider.setEnabled(enabled);
            }
        } catch (IllegalArgumentException e) {
            journal.record(actor, AdminAction.PROVIDER, target, AdminActionLogService.payload("op", "update"),
                    AdminActionOutcome.REFUSED);
            throw e;
        }
        WebProvider saved = repository.save(provider);
        journal.record(actor, AdminAction.PROVIDER, target, AdminActionLogService.payload("op", "update", "code",
                saved.getCode(), "enabled", saved.isEnabled()), AdminActionOutcome.OK);
        return toDto(saved);
    }

    private static WebProviderCode parseCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code est requis");
        }
        try {
            return WebProviderCode.valueOf(code.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Aucun connecteur pour le code : " + code);
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > NAME_MAX) {
            throw new IllegalArgumentException("name requis (" + NAME_MAX + " caractères max)");
        }
        return name.strip();
    }

    /** URL de base facultative ; si présente : https, sans espace. */
    private static String cleanUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String clean = url.strip();
        if (!clean.startsWith("https://") || clean.length() > URL_MAX || clean.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("apiBaseUrl doit commencer par https:// (sans espace, " + URL_MAX + " max)");
        }
        return clean.endsWith("/") ? clean.substring(0, clean.length() - 1) : clean;
    }

    private static AdminProviderDto toDto(WebProvider p) {
        return new AdminProviderDto(p.getId(), p.getCode().name(), p.getName(), p.getApiBaseUrl(), p.isEnabled(),
                p.getCreatedAt());
    }
}
