package fr.ses10doigts.tradeIO5.service.marketdataset.provider.engine;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;

public class InFileDatasets {
    private InFileDatasets(){}

    public static MarketDataSeries load(
            MarketScenario scenario,
            MarketDataRequest request
    ) {
        throw new UnsupportedOperationException("Not Yet Implemented");
    }
}
