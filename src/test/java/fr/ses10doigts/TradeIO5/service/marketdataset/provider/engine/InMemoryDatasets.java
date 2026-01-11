package fr.ses10doigts.tradeIO5.service.marketdataset.provider.engine;

import fr.ses10doigts.tradeIO5.service.support.dataset.dto.memory.TestMarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;

public class InMemoryDatasets {
    private InMemoryDatasets() {}

    public static MarketDataSeries load(
            MarketScenario scenario,
            MarketDataRequest request
    ) {
        return switch (scenario) {
            case FLAT -> TestMarketData.flatMarket(request);
            case UPTREND -> TestMarketData.simpleUptrend(request);
            case DOWNTREND -> TestMarketData.downtrend(request);
            case VOLATILE -> TestMarketData.volatileMarket(request);
        };
    }
}