package fr.ses10doigts.tradeIO5.service.marketdataset.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.marketdataset.MarketDatasetProvider;
import fr.ses10doigts.tradeIO5.service.marketdataset.provider.engine.InFileDatasets;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class FileDatasetProvider implements MarketDatasetProvider {

    private final MarketScenario scenario;


    @Override
    public boolean supports(MarketDataSource source) {
        return MarketDataSource.FILE == source;
    }

    @Override
    public MarketDataSeries load(MarketDataRequest request) {
        // Traduction request → appel fichier
        return InFileDatasets.load(scenario, request);
    }

}
