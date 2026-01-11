package fr.ses10doigts.tradeIO5.service.marketdataset.provider.engine;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;

class InWebApiDatasets {
    private InWebApiDatasets(){}

    public static MarketDataSeries load(
            MarketDataRequest request
    ) {
        throw new UnsupportedOperationException("Forbidden in Test");
    }
}