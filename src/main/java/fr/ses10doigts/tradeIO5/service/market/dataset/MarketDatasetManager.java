package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
class MarketDatasetManager {

    public MarketDatasetManager() {
    }

    // Snapshot : récupère la vue du Bucket au TF demandé
    public MarketDataset snapshot(MarketDatasetRequest request, MarketDatasetState state) {
        List<MarketData> view = state.getBucket().view(request.timeFrame());
        return MarketDataset.builder()
                .request(request)
                .marketDatas(view)
                .lastUpdate(state.getLastUpdate())
                .isComplete(state.isComplete())
                .pair(request.symbol())
                .size(view.size())
                .timeFrame(request.timeFrame())
                .build();
    }


    // Merge : ingestion uniquement, vérification des trous
    public void merge(MarketDatasetState state, List<MarketData> incoming, TimeFrame timeframe) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }

        // Trier par timestamp croissant
        incoming.sort(Comparator.comparing(MarketData::getTimestamp));

        Duration step = timeframe.getDuration();

        MarketData previous = null;
        for (MarketData data : incoming) {
            // Vérification des trous
            if (previous != null) {
                Duration delta = Duration.between(previous.getTimestamp(), data.getTimestamp());
                if (!delta.equals(step)) {
                    state.setComplete(false);
                }
            }
            state.append(data);
            previous = data;
        }

        // Si tout est OK et buffer plein, marquer complet
        if (state.getBucket().view(timeframe).size() >= state.getBucket().getMaxSize()) {
            state.setComplete(true);
        }

        state.setLastUpdate(Instant.now());
    }
}
