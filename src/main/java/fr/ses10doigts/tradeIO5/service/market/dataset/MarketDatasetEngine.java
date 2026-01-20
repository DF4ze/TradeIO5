package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketDatasetEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketDatasetEngine.class);


    private final MarketDatasetCache cache;
    private final MarketDatasetManager manager;
    private final MarketDataProviderRegistry providerRegistry;


    public static final int DEFAULT_LIMIT = 500; // TODO : parametrize


    public MarketDataset refresh(MarketDatasetRequest request) {
        MarketDatasetState state = cache.getState(request);


        if (!isLiveSource(request.source()) && state.isComplete()) {
            return manager.snapshot(request, state);
        }


        MarketDataProvider provider = providerRegistry.getProvider(request.source(), request.providerParam());
        MarketDataset dataset;


        if (isLiveSource(request.source()) && state.getLastUpdate() != null) {
            dataset = provider.loadSince(request, state.getLastUpdate());
        } else {
            dataset = provider.load(request);
        }


        manager.merge(state, dataset.getMarketDatas(), request.timeFrame());


        return manager.snapshot(request, state);
    }


    public List<MarketData> fetchLive(MarketDatasetRequest request, Object providerParam) {
        MarketDataProvider provider = providerRegistry.getProvider(request.source(), providerParam);
        return provider.fetchMarketData(request.symbol(), request.timeFrame(), DEFAULT_LIMIT);
    }


    /**
     * Fournit au consommateur (ex: indicateur) un nombre minimum de bougies pour un TF donné.
     * Tente un fetch incrémental si le Bucket est insuffisant.
     */
    public List<MarketData> getMarketDataForIndicator(MarketDatasetRequest request, MarketDatasetState state, TimeFrame requestedTimeFrame, int requiredCount) {
        long factor = requestedTimeFrame.getNbSeconds() / state.getBucket().getBaseTimeFrame().getNbSeconds();
        int neededBaseCount = (int) (requiredCount * factor);


        List<MarketData> baseView = state.getBucket().view(state.getBucket().getBaseTimeFrame());


        // Vérifier si le Bucket contient assez de bougies
        if (baseView.size() < neededBaseCount) {
            log.info("Bucket insuffisant pour {}: demandé={}, disponible={}, tentative fetch...",
                    state.getPair(), neededBaseCount, baseView.size());


            // Tentative fetch incrémental si provider le permet
            MarketDataProvider provider = providerRegistry.getProvider(request.source(), request.providerParam());
            List<MarketData> fetched = provider.fetchMarketData(request.symbol(), state.getBucket().getBaseTimeFrame(), neededBaseCount - baseView.size());
            if (fetched != null && !fetched.isEmpty()) {
                manager.merge(state, fetched, state.getBucket().getBaseTimeFrame());
                baseView = state.getBucket().view(state.getBucket().getBaseTimeFrame());
            }

            // Si toujours insuffisant, log warning
            if (baseView.size() < neededBaseCount) {
                log.warn("Données insuffisantes pour indicateur {}: demandé={} bougies TF natif={}, disponibles={} après fetch",
                        request.symbol(), requiredCount, state.getBucket().getBaseTimeFrame(), baseView.size());
            }
        }


        int startIndex = Math.max(baseView.size() - neededBaseCount, 0);
        List<MarketData> subBase = baseView.subList(startIndex, baseView.size());


        // Agrégation sur le TF demandé
        Bucket tempBucket = new Bucket(state.getBucket().getBaseTimeFrame(), subBase.size());
        subBase.forEach(tempBucket::append);
        return tempBucket.view(requestedTimeFrame);
    }


    private boolean isLiveSource(MarketDataSource source) {
        return source.getType().isLive();
    }
}
