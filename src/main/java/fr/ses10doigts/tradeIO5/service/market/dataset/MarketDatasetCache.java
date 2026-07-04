package fr.ses10doigts.tradeIO5.service.market.dataset;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
class MarketDatasetCache {

    // Clé = flux natif (symbole + TimeFrame + source + providerParam), PAS la fenêtre
    // demandée : endTime et lookBack varient à chaque appel (ex: Instant.now() à chaque
    // tick) sans changer le flux sous-jacent, et ne doivent donc pas faire partie de la clé.
    private final Map<BucketKey, MarketDatasetState> states
            = new ConcurrentHashMap<>();

    MarketDatasetState getState(BucketKey key) {
        return states.computeIfAbsent(
                key,
                k -> new MarketDatasetState(k.symbol(), Bucket.BASE_MAX_ITEMS)
        );
    }

    public void put(BucketKey key, MarketDatasetState state) {
        states.put(key, state);
    }
}
