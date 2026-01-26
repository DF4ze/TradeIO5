package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;
import fr.ses10doigts.tradeIO5.service.support.dataset.InMemoryMarketDataGenerator;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.List;

@AllArgsConstructor
public class InMemoryMarketDataProvider implements MarketDataProvider {

    private TrendType scenario;

    @Override
    public MarketDataset fullLoad(
            MarketDatasetRequest request
    ) {
        return switch (scenario) {
            case FLAT -> InMemoryMarketDataGenerator.flatMarket(request);
            case UPTREND -> InMemoryMarketDataGenerator.simpleUptrend(request);
            case DOWNTREND -> InMemoryMarketDataGenerator.downtrend(request);
            case VOLATILE -> InMemoryMarketDataGenerator.volatileMarket(request);
        };
    }

    @Override
    public MarketDataset loadSince(MarketDatasetRequest request) {
        return fullLoad(request);
    }

    @Override
    public List<MarketData> fetchMarketData(String symbol, TimeFrame timeframe, Instant time, int limit) {
        MarketDatasetRequest request = new MarketDatasetRequest(
                symbol,
                timeframe,
                limit,
                time,
                MarketDataSource.MEMORY,
                null
        );

        return fullLoad(request).getMarketDatas();
    }
}