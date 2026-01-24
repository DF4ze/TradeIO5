package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.dataset.time.TimeFrameConverter;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MarketDatasetEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketDatasetEngine.class);

    private final MarketDatasetCache cache;
    private final MarketDatasetManager manager;
    private final MarketDataProviderRegistry providerRegistry;
    private final TimeFrameConverter timeFrameConverter;

    public static final int DEFAULT_LIMIT = 500;


    /**
     * Retourne un MarketDataset drivé par la Request.
     */
    public MarketDataset getDataset(MarketDatasetRequest request) {
        int requiredCount = request.lookBack();
        if( requiredCount == 0 ){
            requiredCount = DEFAULT_LIMIT;
        }

        if( request.endTime() == null )
            throw new IllegalArgumentException("endTime cannot be null");

        Instant now = request.endTime();

        MarketDatasetState state = cache.getState(request);
        log.debug("Cache state retreive : {}", state);

        if (shouldFetch(state, request, now)) {
            log.debug("Should Fetch");
            List<MarketData> marketData = fetchDataForBucket(request, state.getBucket().getBaseTimeFrame());
            manager.merge(state, marketData);
            cache.put(request, state);
        }else{

            log.debug("No Fetch...");
        }

        if( !state.getHasDataGap().isEmpty() ) {
            // Vérification des trous et tentative de remplissage via provider
            fillMissingData(state, request);
        }

        // Récupération du snapshot via le manager
        return manager.snapshot(request, state);
    }

    // can throw IllegalStateException when getProvider didn't find
    private List<MarketData> fetchDataForBucket(MarketDatasetRequest request, TimeFrame baseTimeFrame) {

        if (!request.timeFrame().isGreaterOrEqualThan(baseTimeFrame)) {
            throw new IllegalArgumentException("Limit TimeFrame must be >= Base TimeFrame");
        }

        // conversion des limites en BaseTimeFrame si nécessaire
        int limit = request.lookBack() == 0 ? Bucket.BASE_MAX_ITEMS : request.lookBack();

        log.debug("Initial Limit : {} in {} TF", limit, request.timeFrame());
        if (request.timeFrame() != baseTimeFrame) {
            limit = convertLimitToBaseTimeFrame(limit, request.timeFrame(), baseTimeFrame, request.endTime());
        }

        log.debug("Equiv Limit : {} in {} TF", limit, baseTimeFrame);

        MarketDatasetRequest fetchRequest = new MarketDatasetRequest(
                request.symbol(),
                baseTimeFrame,
                limit,
                request.endTime(),
                request.source(),
                request.providerParam()
        );
        log.debug("Request : {}",request);

        // Can throw IllegalState
        MarketDataProvider provider = providerRegistry.getProvider(fetchRequest.source(), fetchRequest.providerParam());

        MarketDataset fetched = null;
        if (provider != null) {
            fetched = provider.loadSince( fetchRequest );
            log.debug("Call to loadSince(request); result size : {}",fetched.getMarketDatas().size());

        }else{
            log.error("Must have thrown an Exception before...!");
        }


        return fetched != null ? fetched.getMarketDatas() : List.of();
    }

    private void fillMissingData(MarketDatasetState state, MarketDatasetRequest request) {

        MarketDataProvider provider = providerRegistry.getProvider(request.source(), request.providerParam());
        if (provider == null) {
            return ;
        }

        Map<Instant, Integer> hasDataGap = state.getHasDataGap();
        for( Map.Entry<Instant, Integer> gap : hasDataGap.entrySet() ) {
            List<MarketData> marketData = provider.fetchMarketData(
                    request.symbol(),
                    state.getBucket().getBaseTimeFrame(),
                    gap.getKey(),
                    gap.getValue()
            );

            if( !marketData.isEmpty() )
                manager.merge(state, marketData);
        }
    }

    private int convertLimitToBaseTimeFrame(int limit, TimeFrame limitTf, TimeFrame baseTf, Instant anchor) {
        return timeFrameConverter.convertLimitToBase(limit, limitTf, baseTf, anchor);
    }

    private boolean shouldFetch(MarketDatasetState state, MarketDatasetRequest request, Instant now) {
        if (state.getLastUpdate() == null) {
            return true;
        }

        if (isLiveSource(request.source())) {
            Instant expiration = now
                    .atZone(TimeFrame.DEFAULT_ZONE)
                    .minus(request.timeFrame().getAmount(), request.timeFrame().getUnit())
                    .toInstant();

            return state.getLastUpdate().isBefore(expiration);
        }

        // historique / backtest : fetch une seule fois
        return false;
    }

    private boolean isLiveSource(MarketDataSource source) {
        return source.getType().isLive();
    }
}
