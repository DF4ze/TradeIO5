package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketDatasetEngine {

    private final MarketDatasetCache cache;
    private final MarketDatasetManager manager;
    private final MarketDataProviderRegistry providerRegistry;

    public static final int DEFAULT_LIMIT = 500; // TODO : Parametrize


    /**
     * Recharge ou crée un dataset à partir de la request.
     * @param request contient la source et paramètres éventuels
     */
    public MarketDataset refresh(MarketDatasetRequest request) {

        MarketDatasetState state = cache.getState(request);

        if (!isLiveSource(request.source()) && state.isComplete() ) {
            // Pas besoin de fetch, renvoyer snapshot directement
            return manager.snapshot(request, state);
        }

        // récupérer le provider via la registry
        MarketDataProvider provider = providerRegistry.getProvider(request.source(), request.providerParam());

        // Charger les données
        // Pour les sources live, fetch incrémental
        MarketDataset dataset;
        if (isLiveSource(request.source()) && state.getLastUpdate() != null) {
            dataset = provider.loadSince(request, state.getLastUpdate()); // fetch seulement ce qui manque
        } else {
            dataset = provider.load(request); // fetch complet
        }

        // merger les données via le manager
        manager.merge(state, dataset.getMarketDatas(), request.timeFrame());

        // retourner un snapshot final
        return manager.snapshot(request, state);
    }

    /**
     * Fetch en direct sans passer par le cache
     */
    public List<MarketData> fetchLive(MarketDatasetRequest request, Object providerParam) {
        MarketDataProvider provider = providerRegistry.getProvider(request.source(), providerParam);
        return provider.fetchMarketData(request.symbol(), request.timeFrame(), DEFAULT_LIMIT);
    }

    private boolean needsRefresh(MarketDatasetState state) {
        // Exemple simple : si dernière update > 5 secondes ou 1 minute, on refresh
        return state.getLastUpdate().isBefore(Instant.now().minusSeconds(60)); // TODO : parametrize
    }

    private boolean isLiveSource(MarketDataSource source) {
        return source.getType().isLive();
    }
}
