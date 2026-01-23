package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ProviderApiClient;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.List;

@AllArgsConstructor
class BinanceMarketDataProvider implements MarketDataProvider {

    private final ProviderApiClient client;


    @Override
    public MarketDataset load(MarketDatasetRequest request) {
        // Ici tu appellerais l'API Binance avec config.apiKey, config.secret...
        throw new UnsupportedOperationException("Not Yet Implemented");
    }

    @Override
    public MarketDataset loadSince(MarketDatasetRequest request, Instant from) {
        // Fetch uniquement les bougies entre "from" et maintenant
        throw new UnsupportedOperationException("Not Yet Implemented");
    }

    @Override
    public List<MarketData> fetchMarketData(
            String symbol,
            TimeFrame timeframe,
            int limit
    ) {
        throw new UnsupportedOperationException("Not Yet Implemented");
    }
}
