package fr.ses10doigts.tradeIO5.model.dto.admin;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

/**
 * Saisie par l'utilisateur de sa propre clé de LECTURE (soldes) : l'utilisateur est toujours celui qui est connecté.
 * {@code toString} masqué : jamais de secret dans un log de requête ou d'exception.
 */
public record OwnReadCredentialRequest(WebProviderCode provider, String apiKey, String secretKey, String passphrase,
                                       Boolean enabled) {

    @Override
    public String toString() {
        return "OwnReadCredentialRequest[provider=" + provider + "]";
    }
}
