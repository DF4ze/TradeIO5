package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssets;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Chargement D1 d'une simulation, UNIQUEMENT via le pipeline {@link MarketDatasetEngine} (cache DB des bougies, fallback signalé
 * par {@link DailyDataset#substituted()}), à la profondeur maximale {@link MarketDataProviderLimits#MAX_D1_HISTORY_CANDLES}.
 * Single-flight par (actif, source demandée) : les demandes concurrentes attendent le chargement déjà en cours au lieu de
 * lancer un second fetch (pas de rafale vers l'exchange).
 */
@Component
@RequiredArgsConstructor
public class DailyDataLoader {

    private record Key(String symbol, MarketDataSource source) {
    }

    private final MarketDatasetEngine datasetEngine;
    private final DomainClock clock;
    private final ExpertAssets assets;
    private final ConcurrentMap<Key, CompletableFuture<DailyDataset>> inFlight = new ConcurrentHashMap<>();

    /** @param preferred source tentée en premier ({@code null} : défauts de l'actif) */
    public DailyDataset load(String symbol, MarketDataSource preferred) {
        Key key = new Key(symbol, preferred);
        CompletableFuture<DailyDataset> mine = new CompletableFuture<>();
        CompletableFuture<DailyDataset> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            // Source de simulation seule (actif non configuré chez elle) : lecture directe, sans ligne asset_provider ni fallback.
            boolean foreign = preferred != null && ExpertAssets.SIMULATION_ONLY_SOURCES.contains(preferred)
                    && assets.isForeignSource(symbol, preferred);
            DailyDataset loaded = foreign
                    ? datasetEngine.getDailyDatasetOnSource(symbol, preferred, MarketDataProviderLimits.MAX_D1_HISTORY_CANDLES, clock.now())
                    : datasetEngine.getDailyDatasetForAsset(symbol, MarketDataProviderLimits.MAX_D1_HISTORY_CANDLES,
                    clock.now(), preferred);
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private static DailyDataset await(CompletableFuture<DailyDataset> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
