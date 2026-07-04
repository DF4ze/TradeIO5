package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.List;

@AllArgsConstructor
class OkxMarketDataProvider implements MarketDataProvider {

    // Candles publiques : MarketDataApiClient n'est jamais construit à partir d'un ApiCredential
    private final MarketDataApiClient client;


    @Override
    public MarketDataset fullLoad(MarketDatasetRequest request) {
        List<MarketData> candles = client.getCandles(
                request.symbol(), request.timeFrame(), null, request.endTime(), request.lookBack());
        return toDataset(request, candles);
    }

    @Override
    public MarketDataset loadSince(MarketDatasetRequest request) {
        // Pas de notion de "since" dédiée dans MarketDatasetRequest à ce jour :
        // on borne simplement par endTime/lookBack, comme fullLoad().
        return fullLoad(request);
    }

    @Override
    public List<MarketData> fetchMarketData(
            String symbol,
            TimeFrame timeframe,
            Instant time, int limit
    ) {
        return client.getCandles(symbol, timeframe, null, time, limit);
    }

    private MarketDataset toDataset(MarketDatasetRequest request, List<MarketData> candles) {
        Instant lastUpdate = candles.isEmpty() ? null : candles.get(candles.size() - 1).getTimestamp();
        return MarketDataset.builder()
                .pair(request.symbol())
                .timeFrame(request.timeFrame())
                .marketDatas(candles)
                .size(candles.size())
                .request(request)
                .lastUpdate(lastUpdate)
                .isComplete(!candles.isEmpty())
                .build();
    }
}
