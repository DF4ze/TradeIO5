package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import lombok.Getter;

/**
 * Base des exceptions levées par un {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient}
 * lorsque le provider signale explicitement une erreur (par opposition à une réponse
 * techniquement réussie mais légitimement vide, cf. Javadoc de
 * {@code MarketDataApiClient#getCandles}).
 * <p>
 * Deux sous-classes concrètes : {@link SymbolNotFoundException} (permanent) et
 * {@link ProviderUnavailableException} (transitoire).
 */
@Getter
public abstract class MarketDataProviderException extends RuntimeException {

    private final MarketDataSource source;
    private final String symbol;

    protected MarketDataProviderException(MarketDataSource source, String symbol, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
        this.symbol = symbol;
    }

}
