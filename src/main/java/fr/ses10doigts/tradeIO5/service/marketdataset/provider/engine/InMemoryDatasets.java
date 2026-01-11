package fr.ses10doigts.tradeIO5.service.marketdataset.provider.engine;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;

public class InMemoryDatasets {

    private InMemoryDatasets() {}

    public static MarketDataSeries load(
            MarketScenario scenario,
            MarketDataRequest request
    ) {
        throw new UnsupportedOperationException("Forbidden Prod access");
    }
}
