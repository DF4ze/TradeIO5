package fr.ses10doigts.tradeIO5.model.dto.admin;

/** Réponse à une saisie de credential : jamais de secret. */
public record CredentialStoredDto(Long id, String provider, String scope, boolean enabled) {
}
