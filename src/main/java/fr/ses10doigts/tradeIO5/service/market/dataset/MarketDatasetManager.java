package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

@Component
class MarketDatasetManager {

    public MarketDatasetManager() {
    }


    MarketDataset snapshot(
            MarketDatasetRequest request,
            MarketDatasetState state
    ) {
        return MarketDataset.builder()
                .request(request)
                .marketDatas(List.copyOf(state.getBuffer()))
                .lastUpdate(state.getLastUpdate())
                .isComplete(state.isComplete())
                .pair(request.symbol())
                .size(state.getBuffer().size())
                .timeFrame(request.timeFrame())
                .isComplete(state.isComplete())
                .build();
    }

    void merge(
            MarketDatasetState state,
            List<MarketData> incoming,
            TimeFrame timeframe
    ) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }

        // 1. Trier par timestamp croissant
        incoming.sort(Comparator.comparing(MarketData::getTimestamp));

        Duration step = timeframe.getDuration();

        for (MarketData data : incoming) {
            appendIfNeeded(state, data, step);
        }

        // 2. Mise à jour des métadonnées
        state.setLastUpdate(Instant.now());
        state.setComplete(isComplete(state, timeframe));
    }

    private void appendIfNeeded(
            MarketDatasetState state,
            MarketData data,
            Duration step
    ) {
        Deque<MarketData> buffer = state.getBuffer();

        if (buffer.isEmpty()) {
            buffer.addLast(data);
            return;
        }

        MarketData last = buffer.peekLast();

        // Doublon exact → on ignore
        if (last.getTimestamp().equals(data.getTimestamp())) {
            return;
        }

        // Donnée plus ancienne → ignore (dataset forward-only)
        if (data.getTimestamp().isBefore(last.getTimestamp())) {
            return;
        }

        // Trou temporel détecté → dataset incomplet
        Duration delta = Duration.between(
                last.getTimestamp(),
                data.getTimestamp()
        );

        if (delta.compareTo(step.multipliedBy(1)) > 0) {
            state.setComplete(false);
        }

        buffer.addLast(data);

        // Eviction circulaire
        while (buffer.size() > state.getMaxSize()) {
            buffer.removeFirst();
        }
    }

    private boolean isComplete(
            MarketDatasetState state,
            TimeFrame timeframe
    ) {
        Deque<MarketData> buffer = state.getBuffer();

        if (buffer.size() < state.getMaxSize()) {
            return false;
        }

        Duration step = timeframe.getDuration();
        MarketData previous = null;

        for (MarketData current : buffer) {
            if (previous != null) {
                Duration delta = Duration.between(
                        previous.getTimestamp(),
                        current.getTimestamp()
                );

                if (!delta.equals(step)) {
                    return false;
                }
            }
            previous = current;
        }

        return true;
    }
}
