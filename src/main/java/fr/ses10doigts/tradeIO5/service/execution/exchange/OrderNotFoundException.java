package fr.ses10doigts.tradeIO5.service.execution.exchange;

/** Aucun ordre avec ce {@code clOrdId} côté exchange (jamais accepté). */
public class OrderNotFoundException extends SpotOrderException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
