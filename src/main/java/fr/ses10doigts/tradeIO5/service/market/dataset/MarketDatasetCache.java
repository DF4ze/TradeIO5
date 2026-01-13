package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
class MarketDatasetCache {

    private final Map<MarketDatasetRequest, MarketDatasetState> states
            = new ConcurrentHashMap<>();

    MarketDatasetState getState(MarketDatasetRequest request) {
        return states.computeIfAbsent(
                request,
                r -> new MarketDatasetState(MarketDatasetEngine.DEFAULT_LIMIT)
        );
    }
}
