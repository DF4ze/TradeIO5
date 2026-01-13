package fr.ses10doigts.tradeIO5.service.market.provider;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketScenario;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.List;

@AllArgsConstructor
class InMemoryMarketDataProvider implements MarketDataProvider {

    private MarketScenario scenario;

    @Override
    public MarketDataset load(
            MarketDatasetRequest request
    ) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " is forbidden Prod access"
        );
    }

    @Override
    public MarketDataset loadSince(MarketDatasetRequest request, Instant from) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support incremental loading"
        );
    }

    @Override
    public List<MarketData> fetchMarketData(String symbol, TimeFrame timeframe, int limit) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support fetching"
        );
    }
}
