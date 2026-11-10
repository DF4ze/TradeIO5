package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;

/** Une clé de lecture (et son wallet) vient d'être enregistrée ou remplacée : publié dans la transaction, écouté après commit. */
public record ReadCredentialStoredEvent(User user, WebProviderCode provider) {
}
