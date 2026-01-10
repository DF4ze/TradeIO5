package fr.ses10doigts.TradeIO5.service.support.dataset.provider;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;

public class FileDatasetProvider
        implements MarketDatasetProvider {

    @Override
    public MarketDataset load(DatasetType datasetType, TimeFrame timeFrame) {
        return null;// csv/json → MarketDataSeries
    }
}
