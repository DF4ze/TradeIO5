package fr.ses10doigts.tradeIO5.service.connector.instrument;

/** Recherche d'instrument impossible (réseau, réponse illisible). Un instrument absent n'est PAS une erreur. */
public class InstrumentLookupException extends RuntimeException {

    public InstrumentLookupException(String message) {
        super(message);
    }

    public InstrumentLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
