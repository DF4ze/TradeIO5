package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.time.LocalDateTime;

/**
 * Credential vue par l'admin : aucun secret. {@code apiKeyHint} = 4 derniers caractères de la clé pour {@code READ}
 * (stockée en clair), {@value #ENCRYPTED_HINT} pour {@code TRADE} (jamais déchiffrée ici).
 */
public record AdminCredentialDto(Long id, Long userId, String username, String provider, String scope, boolean enabled,
                                 LocalDateTime createdAt, String apiKeyHint) {

    public static final String ENCRYPTED_HINT = "chiffrée";
}
