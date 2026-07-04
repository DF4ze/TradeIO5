package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("MarketDataProviderRegistry")
class MarketDataProviderRegistryTest {

    private final MarketDataProviderRegistry registry = new MarketDataProviderRegistry();

    @Test
    void getProvider_binance_doesNotThrow() {
        MarketDataApiClient client = Mockito.mock(MarketDataApiClient.class);
        assertNotNull(assertDoesNotThrow(() -> registry.getProvider(MarketDataSource.BINANCE, client)));
    }

    @Test
    void getProvider_kraken_doesNotThrow() {
        // Avant correctif : IllegalStateException("No factory registered for source KRAKEN")
        MarketDataApiClient client = Mockito.mock(MarketDataApiClient.class);
        assertNotNull(assertDoesNotThrow(() -> registry.getProvider(MarketDataSource.KRAKEN, client)));
    }

    @Test
    void getProvider_okx_doesNotThrow() {
        // Avant correctif : IllegalStateException("No factory registered for source OKX")
        MarketDataApiClient client = Mockito.mock(MarketDataApiClient.class);
        assertNotNull(assertDoesNotThrow(() -> registry.getProvider(MarketDataSource.OKX, client)));
    }
}
