package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Cas transitoire : erreur réseau, timeout, rate limit, 5xx, ou toute erreur provider qui n'est
 * pas un symbole invalide. Contrairement à {@link SymbolNotFoundException}, réessayer plus tard
 * (ou sur le même provider) a du sens.
 */
public class ProviderUnavailableException extends MarketDataProviderException {

    public ProviderUnavailableException(MarketDataSource source, String symbol, String message, Throwable cause) {
        super(source, symbol, message, cause);
    }
}
