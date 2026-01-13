package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ProviderApiClient;
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
                    return new InMemoryMarketDataProvider((MarketScenario) param);
                },
                // File
                MarketDataSource.FILE, param -> {
                    return new InFileMarketDataProvider((String) param);
                },
                // Database (param optional)
                MarketDataSource.DATABASE, param -> {
                    return new InDatabaseMarketDataProvider();
                },
                // Web example : Binance
                MarketDataSource.BINANCE, param -> {
                    return new BinanceMarketDataProvider((ProviderApiClient) param);
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
