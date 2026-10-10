package fr.ses10doigts.tradeIO5.service.execution.exchange;

/**
 * Issue inconnue : timeout, réponse illisible, HTTP 5xx, exchange indisponible. L'ordre a pu être accepté : l'appelant
 * passe l'étape en {@code UNKNOWN} et réconcilie par {@code clOrdId}, il ne renvoie jamais à l'aveugle.
 */
public class ExchangeUnavailableException extends SpotOrderException {

    public ExchangeUnavailableException(String message) {
        super(message);
    }

    public ExchangeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
