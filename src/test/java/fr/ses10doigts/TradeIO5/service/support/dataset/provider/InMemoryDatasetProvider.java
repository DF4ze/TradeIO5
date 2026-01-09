package fr.ses10doigts.TradeIO5.service.support.dataset.provider;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.memory.InMemoryDatasets;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;

public class InMemoryDatasetProvider
        implements MarketDatasetProvider {

    @Override
    public MarketDataset load(DatasetType datasetType) {
        return InMemoryDatasets.load(datasetType);
    }
}
