package fr.ses10doigts.TradeIO5.service.support.dataset.provider;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.memory.InMemoryDatasets;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;

public class InMemoryDatasetProvider
        implements MarketDatasetProvider {

    @Override
    public MarketDataset load(DatasetType datasetType, TimeFrame timeFrame) {
        return InMemoryDatasets.load(datasetType, timeFrame);
    }
}
