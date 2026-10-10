package fr.ses10doigts.tradeIO5.service.execution.exchange;

/** Clé Trade rejetée par l'exchange (clé, signature, passphrase, permission ou IP : codes 50100-50114). */
public class TradeCredentialRejectedException extends SpotOrderException {

    public TradeCredentialRejectedException(String message) {
        super(message);
    }
}
