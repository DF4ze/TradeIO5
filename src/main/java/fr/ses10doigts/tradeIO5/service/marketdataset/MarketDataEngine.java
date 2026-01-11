package fr.ses10doigts.tradeIO5.service.marketdataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;

public interface MarketDataEngine {

    MarketDataSeries getDataset(MarketDataRequest request);

    void refresh(MarketDataRequest request);
}
