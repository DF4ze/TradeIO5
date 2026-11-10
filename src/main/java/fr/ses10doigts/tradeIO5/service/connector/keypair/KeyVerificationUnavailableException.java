package fr.ses10doigts.tradeIO5.service.connector.keypair;

/** La vérification de la clé auprès de l'exchange n'a pas pu avoir lieu (exchange injoignable, provider non déclaré) : rien n'est promu, réessayer plus tard. */
public class KeyVerificationUnavailableException extends RuntimeException {

    public KeyVerificationUnavailableException(String message) {
        super(message);
    }
}
