package fr.ses10doigts.TradeIO5.service.support.dataset.provider;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;

public class FileDatasetProvider
        implements MarketDatasetProvider {

    @Override
    public MarketDataset load(DatasetType datasetType) {
        return null;// csv/json → MarketDataSeries
    }
}
