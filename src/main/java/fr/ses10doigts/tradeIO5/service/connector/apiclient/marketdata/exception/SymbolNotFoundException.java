package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Cas permanent : le provider indique explicitement que le symbole/la paire n'existe pas chez lui
 * (ex: Binance code -1121 "Invalid symbol", Kraken {@code "EQuery:Unknown asset pair"}, OKX code
 * {@code "51001"}). Une bascule vers un autre provider a du sens ; réessayer le même provider
 * plus tard n'en a pas.
 */
public class SymbolNotFoundException extends MarketDataProviderException {

    public SymbolNotFoundException(MarketDataSource source, String symbol, String message) {
        super(source, symbol, message, null);
    }
}
