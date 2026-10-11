package fr.ses10doigts.tradeIO5.model.dto.admin;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

/**
 * Saisie de sa propre clé TRADE par le propriétaire : l'utilisateur est toujours celui qui est connecté (aucun
 * {@code userId} dans le corps). {@code toString} masqué : jamais de secret dans un log de requête ou d'exception.
 */
public record OwnTradeCredentialRequest(WebProviderCode provider, String apiKey, String secretKey, String passphrase,
                                        Boolean enabled) {

    @Override
    public String toString() {
        return "OwnTradeCredentialRequest[provider=" + provider + "]";
    }
}
