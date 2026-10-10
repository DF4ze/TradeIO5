package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

/** Devis de chemin impossible (wallet/credential inutilisable, exchange non pris en charge pour les frais ou le carnet). */
public class PathQuoteUnavailableException extends RuntimeException {

    public PathQuoteUnavailableException(String message) {
        super(message);
    }
}
