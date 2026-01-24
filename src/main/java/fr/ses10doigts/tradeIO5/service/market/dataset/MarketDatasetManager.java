package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSourceType;
import fr.ses10doigts.tradeIO5.service.market.dataset.execution.BacktestExecutionPolicy;
import fr.ses10doigts.tradeIO5.service.market.dataset.execution.ExecutionPolicy;
import fr.ses10doigts.tradeIO5.service.market.dataset.execution.LiveExecutionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Component
class MarketDatasetManager {

    private static final Logger log = LoggerFactory.getLogger(MarketDatasetManager.class);

    private final Map<MarketDataSourceType, ExecutionPolicy> executionPolicies = new HashMap<>();


    // Snapshot : récupère la vue du Bucket au TF demandé
    public MarketDataset snapshot(MarketDatasetRequest request, MarketDatasetState state) {
        // Récupère la vue
        BucketView view = state.getBucket().view(request.timeFrame(), request.endTime());
        log.debug("View from Bucket : {}", view);
        log.debug("Count Bucket : {}", view.size());

        Instant startTime =  request.timeFrame().removeTo(request.endTime(), request.lookBack());
        List<MarketData> filtered = new ArrayList<>();
        for( MarketData data : view.data() ){
            if( (data.getTimestamp().isAfter( startTime ) || data.getTimestamp().equals(startTime))
                && (data.getTimestamp().isBefore( request.endTime() ) || data.getTimestamp().equals(request.endTime()))
            ){
                filtered.add(data);
            }
        }
        log.debug("After filtering, size: {}", filtered.size());


        ExecutionPolicy executionPolicy = getExecutionPolicy(request.source().getType());

        log.debug("executionPolicy decision, isComplete: {}, based on {}", executionPolicy.accept( view.completeness() ), view.completeness());
        return MarketDataset.builder()
                .request(request)
                .marketDatas(filtered)
                .lastUpdate(state.getLastUpdate())
                .isComplete( executionPolicy.accept( view.completeness() ) )
                .pair(request.symbol())
                .size(filtered.size())
                .timeFrame(request.timeFrame())
                .build();
    }

    private ExecutionPolicy getExecutionPolicy(MarketDataSourceType type){
        ExecutionPolicy policy = executionPolicies.get(type);
        if( policy == null ) {
            if (type.isLive()) {
                policy = new LiveExecutionPolicy();
            } else {
                policy = new BacktestExecutionPolicy();
            }
            executionPolicies.put(type, policy);
        }
        return policy;
    }

    // Merge : ingestion uniquement, vérification des trous
    public void merge(
            MarketDatasetState state,
            List<MarketData> incoming
    ) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }

        if( incoming.get(0).getTimeFrame() != state.getBucket().getBaseTimeFrame() ){
            throw new IllegalStateException("Incoming TimeFrame differs from Base TimeFrame");
        }

        incoming.sort(Comparator.comparing(MarketData::getTimestamp));

        Bucket bucket = state.getBucket();

        // Dernière donnée déjà présente dans le bucket (si existe)
        MarketData previous = bucket.isEmpty()
                ? null
                : bucket.peekLast();

        // Vérification des gaps
        int count = 0;
        Instant current;
        boolean isOutOfRange = false;
        for (MarketData data : incoming) {
            current = data.getTimestamp();
            if (previous != null) {
                Instant i1 = previous.getTimestamp();
                Instant i2 = data.getTimestamp();

                int amount = state.getBucket().getBaseTimeFrame().getAmount();
                ChronoUnit unit = state.getBucket().getBaseTimeFrame().getUnit();
                Instant lowerBound = i1.plus(amount, unit);       // i1 + d
                Instant upperBound = i1.plus(amount* 2L, unit); // i1 + 2d

                boolean inRange = !i2.isBefore(lowerBound) && i2.isBefore(upperBound);

                if (!inRange ) {
                    count++;
                    isOutOfRange = true;
                } else if ( isOutOfRange ) { // implicit inRange == true
                    state.getHasDataGap().put(Instant.from(current), count);
                    isOutOfRange = false;
                    count = 0;
                }
            }

            state.getBucket().append(data);
            previous = data;
        }

        state.setLastUpdate(Instant.now());
    }

}
