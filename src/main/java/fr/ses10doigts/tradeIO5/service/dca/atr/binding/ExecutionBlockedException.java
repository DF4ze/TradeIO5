package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Exécution refusée sur un binding dont le couple (actif, exchange) n'est pas exécutable. */
public class ExecutionBlockedException extends RuntimeException {

    public ExecutionBlockedException(String message) {
        super(message);
    }
}
