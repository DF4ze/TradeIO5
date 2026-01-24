package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
class MarketDatasetCache {

    private final Map<MarketDatasetRequest, MarketDatasetState> states
            = new ConcurrentHashMap<>(); // TODO : Peut-etre uniquement la paire en clé...?

    MarketDatasetState getState(MarketDatasetRequest request) {
        return states.computeIfAbsent(
                request,
                r -> new MarketDatasetState(request.symbol(), Bucket.BASE_MAX_ITEMS)
        );
    }

    public void put(MarketDatasetRequest request, MarketDatasetState state) {
        states.put(request, state);
    }
}
