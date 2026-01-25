package fr.ses10doigts.tradeIO5.service.market.dataset;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
@Setter
class MarketDatasetState {

    private final String pair;
    private final Bucket bucket;
    private final int maxSize;
    private final Map<Instant, Integer> hasDataGap;
    private Instant lastUpdate;

    public MarketDatasetState(String pair, int maxSize) {
        this.pair = pair;
        this.maxSize = maxSize;
        this.bucket = new Bucket(maxSize);
        this.hasDataGap = new ConcurrentHashMap<>();
    }

    /* package-private setters pour le manager uniquement */

    /*
    public void append(MarketData data, Instant presumeNow) {
        bucket.append(data);
        lastUpdate = presumeNow;
    }

     */
}
