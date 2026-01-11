package fr.ses10doigts.tradeIO5.service.marketdataset.cache;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import fr.ses10doigts.tradeIO5.service.marketdataset.provider.InMemoryDatasetProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MarketDatasetCache {

    private final Map<MarketDataRequest, MarketDataSeries> datasets
            = new ConcurrentHashMap<>();

    public MarketDataSeries getOrCreate(MarketDataRequest request) {
        if( datasets.containsKey(request) ){
            return datasets.get(request);
        }

        InMemoryDatasetProvider provider = new InMemoryDatasetProvider(MarketScenario.UPTREND);
        MarketDataSeries marketDataSeries = provider.load(request);

        datasets.put(request, marketDataSeries);

        return marketDataSeries;
    }
}
