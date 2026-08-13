package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import fr.ses10doigts.tradeIO5.service.market.dataset.time.TimeFrameConverter;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MarketDatasetEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketDatasetEngine.class);

    private final MarketDatasetCache cache;
    private final MarketDatasetManager manager;
    private final MarketDataProviderRegistry providerRegistry;
    private final TimeFrameConverter timeFrameConverter;
    private final AssetProviderRepository assetProviderRepository;

    // Cf. docs/etudes/etude-fallback-multi-provider-marketdata.md §3 (étape 7c) : même patron que
    // DcaCalculatorService (constructeur + 3 @Qualifier), utilisé par getDatasetForAsset pour
    // résoudre le MarketDataApiClient (providerParam) correspondant au candidat asset_provider en cours.
    private final Map<MarketDataSource, MarketDataApiClient> webClientsBySource;

    public static final int DEFAULT_LIMIT = 500;

    public MarketDatasetEngine(
            MarketDatasetCache cache,
            MarketDatasetManager manager,
            MarketDataProviderRegistry providerRegistry,
            TimeFrameConverter timeFrameConverter,
            @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceClient,
            @Qualifier("cachingKrakenMarketDataApiClient") MarketDataApiClient krakenClient,
            @Qualifier("cachingOkxMarketDataApiClient") MarketDataApiClient okxClient,
            AssetProviderRepository assetProviderRepository
    ) {
        this.cache = cache;
        this.manager = manager;
        this.providerRegistry = providerRegistry;
        this.timeFrameConverter = timeFrameConverter;
        this.assetProviderRepository = assetProviderRepository;
        this.webClientsBySource = new EnumMap<>(MarketDataSource.class);
        this.webClientsBySource.put(MarketDataSource.BINANCE, binanceClient);
        this.webClientsBySource.put(MarketDataSource.KRAKEN, krakenClient);
        this.webClientsBySource.put(MarketDataSource.OKX, okxClient);
    }

    /**
     * Retourne un MarketDataset drivé par la Request. Contrat existant inchangé : {@code source}
     * est obligatoire ici (cf. docs/etudes/etude-fallback-multi-provider-marketdata.md §3 étape 7a) —
     * utiliser {@link #getDatasetForAsset(String, TimeFrame, int, Instant)} pour la résolution
     * automatique du provider via {@code asset_provider}.
     */
    public MarketDataset getDataset(MarketDatasetRequest request) {
        Objects.requireNonNull(request.source(),
                "source obligatoire ici ; utiliser getDatasetForAsset(symbol, ...) pour la résolution automatique");

        if( request.endTime() == null )
            throw new IllegalArgumentException("endTime cannot be null");

        Instant now = request.endTime();

        // Clé = flux natif (symbole + TimeFrame + source + providerParam) — pas la fenêtre
        // demandée (endTime/lookBack), qui elle varie à chaque appel sans changer le flux.
        BucketKey key = BucketKey.from(request);

        MarketDatasetState state = cache.getState(key);
        log.debug("Cache state retreive : {}", state);

        if (shouldFetch(state, request, now)) {
            log.debug("Should Fetch");
            List<MarketData> marketData = fetchDataForBucket(request, state.getBucket().getBaseTimeFrame(), state.getBucket().getMaxSize());
            manager.merge(state, marketData, request.endTime());
            cache.put(key, state);
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

    /**
     * Résout le provider via {@code asset_provider} (candidats ordonnés par {@code priority}),
     * écarte d'abord ceux dont {@code maxHorizonDays} est dépassé par la fenêtre demandée, puis
     * bascule sur le candidat suivant si {@link SymbolNotFoundException} ou
     * {@link ProviderUnavailableException} est levée — sans dupliquer la logique cache/gap de
     * {@link #getDataset(MarketDatasetRequest)}, à qui chaque tentative est déléguée. Cf.
     * docs/etudes/etude-fallback-multi-provider-marketdata.md §3 (étape 7d).
     */
    public MarketDataset getDatasetForAsset(String symbol, TimeFrame timeFrame, int lookBack, Instant endTime) {
        List<AssetProvider> candidates = assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc(symbol)
                .stream()
                .filter(AssetProvider::isEnabled)
                .toList();

        if (candidates.isEmpty()) {
            throw new NoProviderAvailableException(symbol, null); // aucun provider configuré
        }

        int requiredCount = lookBack == 0 ? DEFAULT_LIMIT : lookBack;
        long requestedSpanDays = timeFrame.getUnit().getDuration()
                .multipliedBy(timeFrame.getAmount())
                .multipliedBy(requiredCount)
                .toDays();

        List<AssetProvider> eligible = candidates.stream()
                .filter(c -> c.getMaxHorizonDays() == null || requestedSpanDays <= c.getMaxHorizonDays())
                .toList();

        if (eligible.isEmpty()) {
            throw new NoProviderAvailableException(symbol, null); // aucun candidat ne couvre l'horizon demandé
        }

        MarketDataProviderException lastError = null;
        for (AssetProvider candidate : eligible) {
            MarketDataApiClient client = webClientsBySource.get(candidate.getSource());
            if (client == null) {
                log.warn("Aucun MarketDataApiClient injecté pour la source {} (asset_provider id={}), candidat ignoré.",
                        candidate.getSource(), candidate.getId());
                continue;
            }

            MarketDatasetRequest request = new MarketDatasetRequest(
                    candidate.getProviderSymbol(), timeFrame, lookBack, endTime,
                    candidate.getSource(), client
            );
            // Log de diagnostic (incident 2026-08-13) : confirme quel candidat (source/providerSymbol)
            // est réellement tenté pour cette requête, et le temps total pris par getDataset(...) —
            // permet de savoir si un candidat en particulier (ex: Binance pour ETHUSDT) est celui qui bloque.
            long startNanos = System.nanoTime();
            log.info("getDatasetForAsset({}, {}) : tentative candidat source={} providerSymbol={} priority={}",
                    symbol, timeFrame, candidate.getSource(), candidate.getProviderSymbol(), candidate.getPriority());
            try {
                MarketDataset result = getDataset(request);
                log.info("getDatasetForAsset({}, {}) : candidat source={} OK en {} ms",
                        symbol, timeFrame, candidate.getSource(), (System.nanoTime() - startNanos) / 1_000_000);
                return result;
            } catch (SymbolNotFoundException | ProviderUnavailableException e) {
                log.warn("Provider {} indisponible pour {} ({}) après {} ms : {} — bascule sur le candidat suivant.",
                        candidate.getSource(), symbol, candidate.getProviderSymbol(),
                        (System.nanoTime() - startNanos) / 1_000_000, e.getMessage());
                lastError = e;
            }
        }
        throw new NoProviderAvailableException(symbol, lastError);
    }

    // can throw IllegalStateException when getProvider didn't find
    private List<MarketData> fetchDataForBucket(MarketDatasetRequest request, TimeFrame baseTimeFrame, int bucketMaxSize) {

        if (!request.timeFrame().isGreaterOrEqualThan(baseTimeFrame)) {
            throw new IllegalArgumentException("Limit TimeFrame must be >= Base TimeFrame");
        }

        // conversion des limites en BaseTimeFrame si nécessaire
        int limit = request.lookBack() == 0 ? Bucket.BASE_MAX_ITEMS : request.lookBack();

        log.debug("Initial Limit : {} in {} TF", limit, request.timeFrame());
        if (request.timeFrame() != baseTimeFrame) {
            limit = convertLimitToBaseTimeFrame(limit, request.timeFrame(), baseTimeFrame, request.endTime());
        }

        // Garde-fou : la capacité du Bucket (BASE_MAX_ITEMS, cf. Bucket.java) est dimensionnée
        // pour couvrir tous les lookbacks réalistes. Si une requête la dépasse malgré tout, on ne
        // veut pas tronquer silencieusement l'historique d'un indicateur (cf. incident du
        // 2026-08-13) : on prévient bruyamment plutôt que de plafonner en silence.
        if (limit > bucketMaxSize) {
            log.warn("Requested lookBack for {} at {} converts to {} {} candle(s), which EXCEEDS the bucket capacity ({}). " +
                            "The dataset returned will be truncated to the last {} {} candles — indicator/opinion results may be computed on less history than requested. " +
                            "Consider raising Bucket.BASE_MAX_ITEMS if this becomes a recurring case.",
                    request.symbol(), request.timeFrame(), limit, baseTimeFrame, bucketMaxSize, bucketMaxSize, baseTimeFrame);
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
            // Log de diagnostic (incident 2026-08-13) : point de passage entre l'engine et le
            // provider concret (réseau/DB). Une durée anormalement longue ou absente ici isole
            // le blocage côté provider.loadSince (cf. BinanceMarketDataApiClient) plutôt que côté
            // engine/cache.
            long startNanos = System.nanoTime();
            log.info("fetchDataForBucket : appel provider.loadSince démarré pour {} source={} limit={}",
                    fetchRequest.symbol(), fetchRequest.source(), limit);
            fetched = provider.loadSince( fetchRequest );
            log.info("fetchDataForBucket : provider.loadSince terminé pour {} source={} en {} ms, {} candle(s)",
                    fetchRequest.symbol(), fetchRequest.source(), (System.nanoTime() - startNanos) / 1_000_000,
                    fetched.getMarketDatas().size());

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
                manager.merge(state, marketData, request.endTime());
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
