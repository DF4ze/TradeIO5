package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

@Component
public class MarketDataProviderRegistry {

    private final Map<MarketDataSource, Function<Object, MarketDataProvider>> registry;

    public MarketDataProviderRegistry() {
        this.registry = Map.of(
                // InMemory
                MarketDataSource.MEMORY, param -> {
                    return new InMemoryMarketDataProvider((TrendType) param);
                },
                // File
                MarketDataSource.FILE, param -> {
                    return new InFileMarketDataProvider((String) param);
                },
                // Database (param optional)
                MarketDataSource.DATABASE, param -> {
                    return new InDatabaseMarketDataProvider();
                },
                // Web : Binance (candles publiques, sans ApiCredential)
                MarketDataSource.BINANCE, param -> {
                    return new BinanceMarketDataProvider((MarketDataApiClient) param);
                },
                // Web : Kraken (candles publiques, sans ApiCredential)
                MarketDataSource.KRAKEN, param -> {
                    return new KrakenMarketDataProvider((MarketDataApiClient) param);
                },
                // Web : OKX (candles publiques, sans ApiCredential)
                MarketDataSource.OKX, param -> {
                    return new OkxMarketDataProvider((MarketDataApiClient) param);
                }
        );
    }

    public MarketDataProvider getProvider(MarketDataSource source, Object param) {
        Function<Object, MarketDataProvider> factory = registry.get(source);
        if (factory == null) {
            throw new IllegalStateException("No factory registered for source " + source);
        }
        return factory.apply(param);
    }
}
