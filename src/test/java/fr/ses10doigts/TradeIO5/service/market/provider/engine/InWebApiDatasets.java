package fr.ses10doigts.tradeIO5.service.market.provider.engine;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;

class InWebApiDatasets {
    private InWebApiDatasets(){}

    public static MarketDataset load(
            MarketDatasetRequest request
    ) {
        throw new UnsupportedOperationException("Forbidden in Test");
    }
}