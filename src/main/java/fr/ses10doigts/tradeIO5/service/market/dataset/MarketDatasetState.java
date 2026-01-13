package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

@Getter
class MarketDatasetState {

    private final Deque<MarketData> buffer;
    private final int maxSize;

    private Instant lastUpdate;
    private boolean complete;

    MarketDatasetState(int maxSize) {
        this.maxSize = maxSize;
        this.buffer = new ArrayDeque<>(maxSize);
        this.complete = false;
        this.lastUpdate = Instant.now();
    }

    /* package-private setters pour le manager uniquement */

    void setLastUpdate(Instant lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    void setComplete(boolean complete) {
        this.complete = complete;
    }
}
