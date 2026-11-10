package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.time.LocalDateTime;

/** Paire en attente : clé publique à enregistrer chez l'exchange. Jamais de clé privée. */
public record PendingKeyDto(String provider, String scope, String algorithm, String publicKeyPem, String fingerprint,
                            LocalDateTime createdAt, LocalDateTime expiresAt, String hint) {
}
