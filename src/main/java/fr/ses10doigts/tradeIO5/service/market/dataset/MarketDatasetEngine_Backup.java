package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketDatasetEngine_Backup {

    private static final Logger log = LoggerFactory.getLogger(MarketDatasetEngine_Backup.class);


    private final MarketDatasetCache cache;
    private final MarketDatasetManager manager;
    private final MarketDataProviderRegistry providerRegistry;


    public static final int DEFAULT_LIMIT = 500; // TODO : parametrize
/*

    public MarketDataset refresh(MarketDatasetRequest request) {

    }
    public MarketDataset refresh(MarketDatasetRequest request) {
            MarketDatasetState state = cache.getState(request);
        Instant now = Instant.now(); // ou Clock injectée plus tard

        // 1️⃣ décider s'il faut fetch
        boolean shouldFetch = shouldFetch(state, request, now);

        if (shouldFetch) {
            MarketDataProvider provider =
                    providerRegistry.getProvider(request.source(), request.providerParam());

            MarketDataset fetched;
            if (isLiveSource(request.source()) && state.getLastUpdate() != null) {
                fetched = provider.loadSince(request);
            } else {
                fetched = provider.fullLoad(request);
            }

            manager.merge(state, fetched.getMarketDatas());
        }

        // 2️⃣ snapshot TOUJOURS
        return manager.snapshot(request, state);
    }

    private boolean shouldFetch(
            MarketDatasetState state,
            MarketDatasetRequest request,
            Instant now
    ) {
        if (state.getLastUpdate() == null) {
            return true;
        }

        if (isLiveSource(request.source())) {
            // fetch si données trop vieilles
            Duration age = Duration.between(state.getLastUpdate(), now);
            Duration maxAge = request.timeFrame().getDuration();
            return age.compareTo(maxAge) > 0;
        }

        // backtest / historique : une seule fois
        return false;
    }


    public List<MarketData> fetchLive(MarketDatasetRequest request, Object providerParam) {
        MarketDataProvider provider = providerRegistry.getProvider(request.source(), providerParam);
        return provider.fetchMarketData(request.symbol(), request.timeFrame(), , DEFAULT_LIMIT);
    }



    public MarketDataset getMarketDataForIndicator(
            MarketDatasetRequest request,
            int requiredCount,
            Instant now
    ) {
        Bucket bucket = state.getBucket();

        // 1️⃣ vue base
        BucketView baseView = bucket.view(bucket.getBaseTimeFrame(), now);

        int neededBaseCount =
                requestedTF.toMinutes() / bucket.getBaseTimeFrame().toMinutes()
                        * requiredCount;

        if (baseView.size() < neededBaseCount) {
            MarketDataProvider provider =
                    providerRegistry.getProvider(request.source(), request.providerParam());

            List<MarketData> fetched =
                    provider.fetchMarketData(
                            request.symbol(),
                            bucket.getBaseTimeFrame(),
                            neededBaseCount - baseView.size());

            manager.merge(state, fetched);
            baseView = bucket.view(bucket.getBaseTimeFrame(), now);
        }

        // 2️⃣ sous-vue + agrégation
        List<MarketData> sub =
                baseView.data()
                        .subList(
                                Math.max(0, baseView.size() - neededBaseCount),
                                baseView.size()
                        );

        Bucket temp = new Bucket(bucket.getBaseTimeFrame(), sub.size());
        sub.forEach(temp::append);

        return temp.view(requestedTF, now);
    }



    private boolean isLiveSource(MarketDataSource source) {
        return source.getType().isLive();
    }

 */
}
