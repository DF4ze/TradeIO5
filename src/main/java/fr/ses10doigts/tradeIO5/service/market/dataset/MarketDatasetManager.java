package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSourceType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.dataset.execution.BacktestExecutionPolicy;
import fr.ses10doigts.tradeIO5.service.market.dataset.execution.ExecutionPolicy;
import fr.ses10doigts.tradeIO5.service.market.dataset.execution.LiveExecutionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class MarketDatasetManager {

    private static final Logger log = LoggerFactory.getLogger(MarketDatasetManager.class);

    private final Map<MarketDataSourceType, ExecutionPolicy> executionPolicies = new HashMap<>();


    // Snapshot : récupère la vue du Bucket au TF demandé
    public MarketDataset snapshot(MarketDatasetRequest request, MarketDatasetState state) {
        // Récupère la vue
        BucketView view = state.getBucket().view(request.timeFrame(), request.endTime());
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
            List<MarketData> incoming,
            Instant providedNow
    ) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }

        if( incoming.getFirst().getTimeFrame() != state.getBucket().getBaseTimeFrame() ){
            throw new IllegalStateException("Incoming TimeFrame differs from Base TimeFrame");
        }

        // incoming peut être une liste immuable (ex: Stream.toList() côté fournisseur de
        // candles) : on trie une copie mutable plutôt que la liste reçue en paramètre, pour
        // éviter un UnsupportedOperationException sur List.sort(...).
        incoming = new ArrayList<>(incoming);
        incoming.sort(Comparator.comparing(MarketData::getTimestamp));

        Bucket bucket = state.getBucket();
        TimeFrame baseTimeFrame = bucket.getBaseTimeFrame();
        boolean hasOlderHistory = !bucket.isEmpty()
                && incoming.getFirst().getTimestamp().isBefore(bucket.peekFirst().getTimestamp());

        // Un trou n'existe que vers l'avant : entre la dernière bougie connue et la première
        // bougie entrante plus récente (ou entre deux bougies entrantes). Le chevauchement avec
        // l'existant (refetch) n'est jamais un trou.
        MarketData previous = bucket.peekLast();
        int totalEvicted = 0;
        for (MarketData data : incoming) {
            if (previous != null && data.getTimestamp().isAfter(previous.getTimestamp())) {
                long missing = missingCandles(previous.getTimestamp(), data.getTimestamp(), baseTimeFrame);
                if (missing > 0) {
                    state.getHasDataGap().put(data.getTimestamp(), (int) missing);
                }
            }
            totalEvicted += bucket.append(data);
            previous = bucket.peekLast();
        }

        // Historique plus ancien que le début du buffer (requête plus profonde que la précédente)
        if (hasOlderHistory) {
            int inserted = bucket.fill(incoming);
            log.debug("Bucket deepened : {} older candle(s) inserted", inserted);
        }

        if (totalEvicted > 0) {
            log.warn("Bucket capacity reached (maxSize={}): {} oldest H1 candle(s) evicted while merging {} incoming candle(s). " +
                            "Consider capping the request's lookBack to what the base-timeframe buffer can hold.",
                    state.getBucket().getMaxSize(), totalEvicted, incoming.size());
        }

        state.setLastUpdate(providedNow);
    }

    /**
     * Comble un trou : insère les bougies manquantes dans le Bucket. Ne touche pas à lastUpdate
     * (ce n'est pas un refetch de la fin de série ; le repousser retardait les vrais refetchs).
     */
    public void fillGap(MarketDatasetState state, List<MarketData> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }
        int inserted = state.getBucket().fill(candles);
        log.debug("Gap fill : {} candle(s) inserted", inserted);
    }

    private long missingCandles(Instant from, Instant to, TimeFrame baseTimeFrame) {
        long step = baseTimeFrame.getUnit().getDuration().multipliedBy(baseTimeFrame.getAmount()).toSeconds();
        return Duration.between(from, to).getSeconds() / step - 1;
    }

}
