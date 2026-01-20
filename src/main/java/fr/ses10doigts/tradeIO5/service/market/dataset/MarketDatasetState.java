package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
class MarketDatasetState {

    private final String pair;
    private final Bucket bucket;
    private final int maxSize;

    private Instant lastUpdate;
    private boolean isComplete;

    public MarketDatasetState(String pair, int maxSize) {
        this.pair = pair;
        this.maxSize = maxSize;
        this.bucket = new Bucket(maxSize);
        this.isComplete = false;
    }

    /* package-private setters pour le manager uniquement */

    public void append(MarketData data) {
        bucket.append(data);
        lastUpdate = Instant.now();
    }
}
