package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
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
            List<MarketData> marketData = fetchDataForBucket(request, state);
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

    /**
     * Série D1 NATIVE (hors Bucket H1) d'un actif, pour le Rainbow : un seul appel par exchange et par jour grâce au
     * cache DB des bougies, {@code lookBack} bougies (720 j = limite native Kraken). Sans {@code preferred} : candidats
     * {@code asset_provider} par {@code priority}, fallback silencieux (défauts). Avec {@code preferred} (source du lien) :
     * cette source est tentée en premier, sa ligne {@code asset_provider} est créée à la volée si absente (exchanges de
     * confiance seulement, cf. {@link ProviderSymbolMapper}) ; en cas d'échec, fallback sur les autres candidats AVEC un
     * WARN bruyant et {@link DailyDataset#substituted()} = {@code true} (le signal n'est plus celui de l'exchange d'exécution).
     * Cf. docs/etudes/etude-source-candles-provider-du-lien.md.
     */
    public DailyDataset getDailyDatasetForAsset(String symbol, int lookBack, Instant endTime, MarketDataSource preferred) {
        List<AssetProvider> all = assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc(symbol);
        List<AssetProvider> ordered = new ArrayList<>(all.stream().filter(AssetProvider::isEnabled).toList());

        if (preferred != null) {
            AssetProvider wanted = resolvePreferred(symbol, preferred, all);
            if (wanted != null) {
                ordered.removeIf(c -> c.getSource() == preferred);
                ordered.addFirst(wanted);
            } else {
                log.warn("Source {} demandée pour {} mais indisponible (désactivée ou non fiable) : fallback sur les défauts.",
                        preferred, symbol);
            }
        }
        if (ordered.isEmpty()) {
            throw new NoProviderAvailableException(symbol, null);
        }

        MarketDataProviderException lastError = null;
        for (AssetProvider candidate : ordered) {
            MarketDataApiClient client = webClientsBySource.get(candidate.getSource());
            if (client == null) {
                log.warn("Aucun MarketDataApiClient injecté pour la source {} (asset_provider id={}), candidat ignoré.",
                        candidate.getSource(), candidate.getId());
                continue;
            }
            try {
                MarketDataset dataset = fetchDaily(candidate, client, lookBack, endTime);
                boolean substituted = preferred != null && candidate.getSource() != preferred;
                if (substituted) {
                    log.warn("⚠️⚠️⚠️ SUBSTITUTION DE SOURCE {} : candles D1 demandées sur {} mais servies par {} ({}). "
                                    + "Le signal n'est PAS calculé sur l'exchange d'exécution.",
                            symbol, preferred, candidate.getSource(), candidate.getProviderSymbol());
                }
                return new DailyDataset(dataset, candidate.getSource(), candidate.getProviderSymbol(), substituted);
            } catch (SymbolNotFoundException | ProviderUnavailableException e) {
                log.warn("D1 {} : provider {} ({}) indisponible : {}", symbol, candidate.getSource(),
                        candidate.getProviderSymbol(), e.getMessage());
                lastError = e;
            }
        }
        throw new NoProviderAvailableException(symbol, lastError);
    }

    /** Ligne {@code asset_provider} de la source demandée : existante et activée, sinon créée à la volée (source de confiance). */
    private AssetProvider resolvePreferred(String symbol, MarketDataSource preferred, List<AssetProvider> all) {
        for (AssetProvider row : all) {
            if (row.getSource() == preferred) {
                return row.isEnabled() ? row : null; // kill switch manuel respecté
            }
        }
        String providerSymbol = ProviderSymbolMapper.forSource(symbol, preferred);
        if (providerSymbol == null || !webClientsBySource.containsKey(preferred) || all.isEmpty()) {
            return null;
        }
        int priority = all.stream().mapToInt(AssetProvider::getPriority).max().orElse(0) + 1;
        AssetProvider created = assetProviderRepository.save(AssetProvider.builder()
                .asset(all.getFirst().getAsset())
                .source(preferred)
                .providerSymbol(providerSymbol)
                .priority(priority)
                .maxHorizonDays(preferred == MarketDataSource.KRAKEN ? MarketDataProviderLimits.KRAKEN_H1_MAX_HORIZON_DAYS : null)
                .enabled(true)
                .build());
        log.warn("asset_provider créé à la volée : {} / {} ({}), priorité {} (les défauts ne changent pas).",
                symbol, preferred, providerSymbol, priority);
        return created;
    }

    private MarketDataset fetchDaily(AssetProvider candidate, MarketDataApiClient client, int lookBack, Instant endTime) {
        Instant lastDay = endTime.truncatedTo(ChronoUnit.DAYS);
        Instant since = TimeFrame.D1.removeTo(lastDay, lookBack - 1L);
        List<MarketData> candles = client.getCandles(candidate.getProviderSymbol(), TimeFrame.D1, since, endTime, lookBack);
        if (candles == null || candles.isEmpty()) {
            throw new ProviderUnavailableException(candidate.getSource(), candidate.getProviderSymbol(),
                    "aucune bougie D1 reçue", null);
        }
        MarketDatasetRequest request = new MarketDatasetRequest(candidate.getProviderSymbol(), TimeFrame.D1, lookBack,
                endTime, candidate.getSource(), client);
        return MarketDataset.builder()
                .pair(candidate.getProviderSymbol())
                .timeFrame(TimeFrame.D1)
                .marketDatas(candles)
                .size(candles.size())
                .request(request)
                .lastUpdate(endTime)
                .isComplete(candles.size() >= lookBack)
                .build();
    }

    // can throw IllegalStateException when getProvider didn't find
    private List<MarketData> fetchDataForBucket(MarketDatasetRequest request, MarketDatasetState state) {
        Bucket bucket = state.getBucket();
        TimeFrame baseTimeFrame = bucket.getBaseTimeFrame();
        int bucketMaxSize = bucket.getMaxSize();

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

        // Refetch incrémental : si le Bucket couvre déjà la profondeur demandée, on ne redemande
        // que la queue (bougies manquantes + la dernière, à rafraîchir). Sinon fetch complet.
        boolean incremental = !bucket.isEmpty() && state.getFetchedDepth() >= limit;
        int fetchLimit = incremental ? incrementalLimit(bucket, baseTimeFrame, request.endTime(), limit) : limit;

        MarketDatasetRequest fetchRequest = new MarketDatasetRequest(
                request.symbol(),
                baseTimeFrame,
                fetchLimit,
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
                    fetchRequest.symbol(), fetchRequest.source(), fetchLimit);
            fetched = provider.loadSince( fetchRequest );
            log.info("fetchDataForBucket : provider.loadSince terminé pour {} source={} en {} ms, {} candle(s)",
                    fetchRequest.symbol(), fetchRequest.source(), (System.nanoTime() - startNanos) / 1_000_000,
                    fetched.getMarketDatas().size());

            if (!incremental) {
                state.setFetchedDepth(limit);
            }
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

        // Chaque trou n'est tenté qu'une fois (retiré avant l'appel) : un trou non comblable
        // (provider sans l'historique) ne doit pas être refetché à chaque getDataset().
        Iterator<Map.Entry<Instant, Integer>> gaps = state.getHasDataGap().entrySet().iterator();
        while (gaps.hasNext()) {
            Map.Entry<Instant, Integer> gap = gaps.next();
            gaps.remove();
            // +1 : la borne de fin (première bougie après le trou) est comprise dans la réponse
            manager.fillGap(state, provider.fetchMarketData(
                    request.symbol(),
                    state.getBucket().getBaseTimeFrame(),
                    gap.getKey(),
                    gap.getValue() + 1
            ));
        }
    }

    /** Bougies de base à redemander : celles écoulées depuis la dernière stockée + elle-même + marge. */
    private int incrementalLimit(Bucket bucket, TimeFrame baseTimeFrame, Instant endTime, int fullLimit) {
        long missing = Duration.between(bucket.peekLast().getTimestamp(), endTime).getSeconds() / stepSeconds(baseTimeFrame);
        return (int) Math.min(fullLimit, Math.max(missing, 0) + 2);
    }

    private long stepSeconds(TimeFrame timeFrame) {
        return timeFrame.getUnit().getDuration().multipliedBy(timeFrame.getAmount()).toSeconds();
    }

    private int convertLimitToBaseTimeFrame(int limit, TimeFrame limitTf, TimeFrame baseTf, Instant anchor) {
        return timeFrameConverter.convertLimitToBase(limit, limitTf, baseTf, anchor);
    }

    private boolean shouldFetch(MarketDatasetState state, MarketDatasetRequest request, Instant now) {
        if (state.getLastUpdate() == null) {
            return true;
        }

        if (isLiveSource(request.source())) {
            // Frais tant que le dernier fetch date de la bougie de base en cours (ou plus récent) :
            // chaque nouvelle bougie de base déclenche un refetch, quel que soit le TF demandé
            // (la bougie D1 d'un bench à 23h55 doit voir la H1 de 23h, pas celle de 04h).
            long step = stepSeconds(Bucket.BASE_TIME_FRAME);
            Instant currentBaseCandle = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), step) * step);
            return state.getLastUpdate().isBefore(currentBaseCandle);
        }

        // historique / backtest : fetch une seule fois
        return false;
    }

    private boolean isLiveSource(MarketDataSource source) {
        return source.getType().isLive();
    }
}
