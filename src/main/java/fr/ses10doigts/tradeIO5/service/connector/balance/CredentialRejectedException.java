package fr.ses10doigts.tradeIO5.service.connector.balance;

/** L'exchange a rejeté la clé API (clé, signature, passphrase, permission ou IP refusées) : credential à corriger. */
public class CredentialRejectedException extends BalanceUnavailableException {

    public CredentialRejectedException(String message) {
        super(message);
    }
}
