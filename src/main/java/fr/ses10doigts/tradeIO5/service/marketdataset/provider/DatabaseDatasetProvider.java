package fr.ses10doigts.tradeIO5.service.marketdataset.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.marketdataset.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.service.marketdataset.provider.engine.InDatabaseDatasets;
import org.springframework.stereotype.Component;

@Component
public class DatabaseDatasetProvider
        implements MarketDatasetProvider {

    @Override
    public boolean supports(MarketDataSource source) {
        return MarketDataSource.DATABASE == source;
    }

    @Override
    public MarketDataSeries load(MarketDataRequest request) {
        // Traduction request → SQL
        // mapping résultat → MarketDataset

        return InDatabaseDatasets.load(request);
    }
}
