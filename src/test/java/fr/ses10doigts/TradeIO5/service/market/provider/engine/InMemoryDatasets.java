package fr.ses10doigts.tradeIO5.service.market.provider.engine;

import fr.ses10doigts.tradeIO5.service.support.dataset.InMemoryMarketDataGenerator;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TrendType;

public class InMemoryDatasets {
    private InMemoryDatasets() {}

    public static MarketDataset load(
            TrendType scenario,
            MarketDatasetRequest request
    ) {
        return switch (scenario) {
            case FLAT -> InMemoryMarketDataGenerator.flatMarket(request);
            case UPTREND -> InMemoryMarketDataGenerator.simpleUptrend(request);
            case DOWNTREND -> InMemoryMarketDataGenerator.downtrend(request);
            case VOLATILE -> InMemoryMarketDataGenerator.volatileMarket(request);
        };
    }
}