package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.dataset.time.TimeFrameConverter;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Régression de l'incident du 2026-10-06 : le Bucket D1 se figeait (bench Rainbow sans bougie du jour
 * à partir du 06/10) alors que le service tournait. Engine/Cache/Manager/Bucket réels, seul le provider
 * est simulé (comportement Binance : les {@code limit} dernières bougies H1 jusqu'à {@code endTime},
 * la dernière étant partielle).
 */
@DisplayName("Market Dataset - fraîcheur du Bucket dans la durée")
class MarketDatasetEngineFreshnessTest {

    private static final Instant DAY0 = Instant.parse("2026-09-01T00:00:00Z");

    private FakeProvider provider;
    private MarketDatasetEngine engine;

    @BeforeEach
    void setup() {
        provider = new FakeProvider();
        MarketDataProviderRegistry registry = mock(MarketDataProviderRegistry.class);
        when(registry.getProvider(any(), any())).thenReturn(provider);
        engine = new MarketDatasetEngine(
                new MarketDatasetCache(), new MarketDatasetManager(), registry, new TimeFrameConverter(),
                mock(MarketDataApiClient.class), mock(MarketDataApiClient.class), mock(MarketDataApiClient.class),
                mock(AssetProviderRepository.class));
    }

    @Test
    @DisplayName("Appels type prod (bench 23h55 + 00h05, orchestrateur 04h00) : la bougie D1 du jour est toujours là, jour après jour")
    void d1AdvancesEveryDay_withJitteredSchedulerTimes() {
        assertD1AdvancesDaily(1_000_000L);
    }

    @Test
    @DisplayName("Idem avec des horaires exacts (aucun jitter de nanosecondes à exploiter)")
    void d1AdvancesEveryDay_withExactTimes() {
        assertD1AdvancesDaily(0L);
    }

    @Test
    @DisplayName("La bougie H1 en cours, stockée partielle, est remplacée par sa version finale à l'heure suivante")
    void partialCandleIsReplacedByFinalOne() {
        Instant tenThirty = Instant.parse("2026-10-08T10:30:00Z");
        MarketDataset first = get(TimeFrame.H1, 10, tenThirty);
        assertEquals(hourIndex("2026-10-08T10:00:00Z") + 0.5, first.getMarketDatas().getLast().getClose().doubleValue());

        MarketDataset second = get(TimeFrame.H1, 10, Instant.parse("2026-10-08T11:05:00Z"));
        List<MarketData> candles = second.getMarketDatas();
        assertEquals(hourIndex("2026-10-08T11:00:00Z") + 0.5, candles.getLast().getClose().doubleValue());
        assertEquals((double) hourIndex("2026-10-08T10:00:00Z"), candles.get(candles.size() - 2).getClose().doubleValue());
    }

    @Test
    @DisplayName("Une requête plus profonde que la précédente complète l'historique (au lieu de rester tronqué)")
    void deeperRequestExtendsHistory() {
        Instant t = Instant.parse("2026-10-08T10:30:00Z");
        MarketDataset shallow = get(TimeFrame.D1, 10, t);
        MarketDataset deep = get(TimeFrame.D1, 40, t.plus(1, ChronoUnit.HOURS));

        assertTrue(deep.getMarketDatas().size() >= 40, "size=" + deep.getMarketDatas().size());
        assertTrue(deep.getMarketDatas().getFirst().getTimestamp().isBefore(shallow.getMarketDatas().getFirst().getTimestamp()));
    }

    @Test
    @DisplayName("Après le fetch complet initial, les refetchs sont incrémentaux (quelques bougies, pas des milliers)")
    void refetchIsIncrementalOnceDepthIsCovered() {
        Instant t = Instant.parse("2026-10-08T10:30:00Z");
        get(TimeFrame.D1, 60, t);
        int fullLimit = provider.loadLimits.getLast();
        assertTrue(fullLimit > 1000, "fullLimit=" + fullLimit);

        get(TimeFrame.D1, 60, t.plus(1, ChronoUnit.HOURS));
        get(TimeFrame.D1, 30, t.plus(5, ChronoUnit.HOURS));

        assertEquals(3, provider.loadLimits.size());
        assertTrue(provider.loadLimits.get(1) <= 5, "limit=" + provider.loadLimits.get(1));
        assertTrue(provider.loadLimits.get(2) <= 8, "limit=" + provider.loadLimits.get(2));
    }

    @Test
    @DisplayName("Pas de refetch tant qu'on reste dans la même bougie H1")
    void noRefetchWithinSameBaseCandle() {
        get(TimeFrame.D1, 20, Instant.parse("2026-10-08T10:05:00Z"));
        get(TimeFrame.D1, 20, Instant.parse("2026-10-08T10:55:00Z"));

        assertEquals(1, provider.loadLimits.size());
    }

    @Test
    @DisplayName("H1, D1 et W1 d'un même symbole partagent un seul Bucket : un seul fetch par bougie H1, pas un par TF")
    void allTimeFramesShareOneBucket() {
        Instant t = Instant.parse("2026-10-08T10:05:00Z");
        get(TimeFrame.D1, 20, t);
        MarketDataset h1 = get(TimeFrame.H1, 10, t.plus(10, ChronoUnit.MINUTES));
        get(TimeFrame.W1, 4, t.plus(20, ChronoUnit.MINUTES));

        assertEquals(1, provider.loadLimits.size());
        assertEquals(10, h1.getMarketDatas().size());
        assertEquals(Instant.parse("2026-10-08T10:00:00Z"), h1.getMarketDatas().getLast().getTimestamp());
    }

    @Test
    @DisplayName("Une requête plus profonde via un autre TF déclenche un fetch complet (la profondeur est partagée)")
    void deeperRequestFromAnotherTimeFrameDeepensSharedBucket() {
        Instant t = Instant.parse("2026-10-08T10:05:00Z");
        get(TimeFrame.H1, 100, t);
        MarketDataset d1 = get(TimeFrame.D1, 30, t.plus(1, ChronoUnit.HOURS));

        assertEquals(2, provider.loadLimits.size());
        assertTrue(provider.loadLimits.getLast() > 600, "limit=" + provider.loadLimits.getLast());
        assertTrue(d1.getMarketDatas().size() >= 30, "size=" + d1.getMarketDatas().size());
    }

    /* ===== scénario ===== */

    private void assertD1AdvancesDaily(long jitterNanosPerDay) {
        for (int day = 0; day < 10; day++) {
            Instant dayStart = DAY0.plus(day, ChronoUnit.DAYS);
            get(TimeFrame.D1, 60, dayStart.plus(5, ChronoUnit.MINUTES).plusNanos(day * jitterNanosPerDay));
            get(TimeFrame.D1, 50, dayStart.plus(4, ChronoUnit.HOURS).plusNanos(day * jitterNanosPerDay));

            MarketDataset bench = get(TimeFrame.D1, 60,
                    dayStart.plus(23, ChronoUnit.HOURS).plus(55, ChronoUnit.MINUTES).plusNanos(day * jitterNanosPerDay));

            assertEquals(dayStart, bench.getMarketDatas().getLast().getTimestamp(),
                    "bougie D1 du jour absente au jour " + day);
        }
    }

    private MarketDataset get(TimeFrame timeFrame, int lookBack, Instant endTime) {
        return engine.getDataset(new MarketDatasetRequest(
                "BTCUSDT", timeFrame, lookBack, endTime, MarketDataSource.BINANCE, null));
    }

    private static long hourIndex(String instant) {
        return Instant.parse(instant).getEpochSecond() / 3600;
    }

    /* ===== provider simulé ===== */

    private static class FakeProvider implements MarketDataProvider {

        final List<Integer> loadLimits = new ArrayList<>();

        @Override
        public MarketDataset fullLoad(MarketDatasetRequest request) {
            return loadSince(request);
        }

        @Override
        public MarketDataset loadSince(MarketDatasetRequest request) {
            loadLimits.add(request.lookBack());
            List<MarketData> candles = candles(request.endTime(), request.lookBack());
            return new MarketDataset(request.symbol(), request.timeFrame(), candles, candles.size(), request, request.endTime(), true);
        }

        @Override
        public List<MarketData> fetchMarketData(String symbol, TimeFrame timeframe, Instant time, int limit) {
            return candles(time, limit);
        }

        /** Les {@code limit} dernières bougies H1 jusqu'à {@code end} ; la dernière (en cours) est partielle (close = index + 0.5). */
        private List<MarketData> candles(Instant end, int limit) {
            Instant lastHour = end.truncatedTo(ChronoUnit.HOURS);
            List<MarketData> list = new ArrayList<>(limit);
            for (int i = limit - 1; i >= 0; i--) {
                Instant ts = lastHour.minus(i, ChronoUnit.HOURS);
                double close = ts.getEpochSecond() / 3600.0 + (i == 0 ? 0.5 : 0.0);
                BigDecimal price = BigDecimal.valueOf(close);
                list.add(MarketData.builder()
                        .pair("BTCUSDT").timeFrame(TimeFrame.H1).timestamp(ts)
                        .open(price).high(price).low(price).close(price).volume(BigDecimal.ONE)
                        .build());
            }
            return list;
        }
    }
}
