package fr.ses10doigts.TradeIO5.service.support.dataset.provider;

import fr.ses10doigts.TradeIO5.service.support.dataset.dto.DatasetType;
import fr.ses10doigts.TradeIO5.service.support.dataset.dto.MarketDataset;

public interface MarketDatasetProvider {
    MarketDataset load(DatasetType datasetType);
}
