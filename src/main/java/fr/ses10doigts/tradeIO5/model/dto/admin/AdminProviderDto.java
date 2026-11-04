package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.time.LocalDateTime;
import java.util.List;

/** Provider déclaré vu par l'admin (aucun secret) et liste des codes de connecteur encore non déclarés. */
public record AdminProviderDto(Long id, String code, String name, String apiBaseUrl, boolean enabled, LocalDateTime createdAt) {

    /** Réponse de liste : providers déclarés + codes disponibles (valeurs de {@code WebProviderCode} sans ligne en base). */
    public record Listing(List<AdminProviderDto> providers, List<String> available) {
    }
}
