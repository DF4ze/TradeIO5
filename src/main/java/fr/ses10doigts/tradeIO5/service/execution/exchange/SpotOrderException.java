package fr.ses10doigts.tradeIO5.service.execution.exchange;

/** Base des erreurs du port d'ordres. Les messages ne contiennent jamais de secret ni le corps signé. */
public abstract class SpotOrderException extends RuntimeException {

    protected SpotOrderException(String message) {
        super(message);
    }

    protected SpotOrderException(String message, Throwable cause) {
        super(message, cause);
    }
}
