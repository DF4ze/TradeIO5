package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import fr.ses10doigts.tradeIO5.service.market.dataset.time.TimeFrameConverter;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@DisplayName("Market Dataset - *Engine")
@ExtendWith(MockitoExtension.class)
class MarketDatasetEngineTest {
    @Mock
    MarketDatasetCache cache;

    @Mock
    MarketDatasetManager manager;

    @Mock
    MarketDataProviderRegistry providerRegistry;

    @Mock
    MarketDataProvider provider;

    @Mock
    MarketDatasetState state;

    @Mock
    MarketDataApiClient binanceClient;

    @Mock
    MarketDataApiClient krakenClient;

    @Mock
    MarketDataApiClient okxClient;

    @Mock
    AssetProviderRepository assetProviderRepository;

    MarketDatasetEngine engine;

    MarketDatasetRequest request;
    static Instant now;

    @BeforeAll
    static void init(){
        now = Instant.now();
    }

    @BeforeEach
    void setup() {
        engine = new MarketDatasetEngine(
                cache, manager, providerRegistry, new TimeFrameConverter(),
                binanceClient, krakenClient, okxClient, assetProviderRepository);

        request = new MarketDatasetRequest(
                "BTCUSDT",
                TimeFrame.H1,
                100,
                now,
                MarketDataSource.BINANCE,
                null
        );
    }

    @Test
    void shouldThrowExceptionWhenEndTimeIsNull() {
        MarketDatasetRequest invalid = new MarketDatasetRequest(
                "BTCUSDT",
                TimeFrame.M1,
                100,
                null,
                MarketDataSource.BINANCE,
                null
        );

        assertThrows(IllegalArgumentException.class,
                () -> engine.getDataset(invalid));
    }

    @Test
    @DisplayName("getDataset(request) avec source == null lève NullPointerException (contrat 7a)")
    void shouldThrowNullPointerExceptionWhenSourceIsNull() {
        MarketDatasetRequest invalid = new MarketDatasetRequest(
                "BTCUSDT",
                TimeFrame.H1,
                100,
                now,
                null,
                null
        );

        assertThrows(NullPointerException.class,
                () -> engine.getDataset(invalid));
    }

    @Test
    void shouldFetchWhenLastUpdateIsNull() {
        when(cache.getState(any(BucketKey.class)))
                .thenReturn(state);
        when(state.getLastUpdate()).thenReturn(null);
        Bucket bucket = new Bucket(TimeFrame.H1, Bucket.BASE_MAX_ITEMS);
        when(state.getBucket()).thenReturn(bucket);
        when(state.getHasDataGap()).thenReturn(Map.of());

        when(providerRegistry.getProvider(any(), any())).thenReturn(provider);

        MarketDataset fetched = new MarketDataset(
                "BTCUSD",
                TimeFrame.H1,
                List.of(mock(MarketData.class)),
                10,
                request,
                now,
                false
        );
        when(provider.loadSince(any())).thenReturn(fetched);

        MarketDataset snapshot = mock(MarketDataset.class);
        when(manager.snapshot(request, state)).thenReturn(snapshot);

        MarketDataset result = engine.getDataset(request);

        verify(provider).loadSince(any());
        verify(manager).merge(eq(state), anyList(), any(Instant.class));
        verify(manager).snapshot(request, state);
        assertSame(snapshot, result);
    }

    @Test
    void shouldNotFetchWhenCacheIsFreshForLiveSource() {
        when(cache.getState(BucketKey.from(request))).thenReturn(state);
        when(state.getLastUpdate()).thenReturn(now.minusSeconds(10));
        when(state.getHasDataGap()).thenReturn(Map.of());

        MarketDataset snapshot = mock(MarketDataset.class);
        when(manager.snapshot(request, state)).thenReturn(snapshot);

        MarketDataset result = engine.getDataset(request);

        verifyNoInteractions(providerRegistry);
        verify(manager, never()).merge(any(), any(), any(Instant.class));
        verify(manager).snapshot(request, state);
        assertSame(snapshot, result);
    }

    @Test
    void shouldFillMissingDataWhenGapExists() {
        when(cache.getState(BucketKey.from(request))).thenReturn(state);
        when(state.getLastUpdate()).thenReturn(now.minusSeconds(3600));
        when(state.getBucket()).thenReturn(mock(Bucket.class));

        when(state.getHasDataGap()).thenReturn(
                Map.of(now.minusSeconds(300), 10)
        );

        when(providerRegistry.getProvider(any(), any())).thenReturn(provider);
        when(provider.fetchMarketData(any(), any(), any(), anyInt()))
                .thenReturn(List.of(mock(MarketData.class)));

        MarketDataset snapshot = mock(MarketDataset.class);
        when(manager.snapshot(request, state)).thenReturn(snapshot);

        engine.getDataset(request);

        verify(manager, atLeastOnce()).merge(eq(state), anyList(), any(Instant.class));
        verify(manager).snapshot(request, state);
    }

    @Test
    void shouldNeverFetchForHistoricalSource() {
        MarketDatasetRequest historical = new MarketDatasetRequest(
                "BTCUSDT",
                TimeFrame.M1,
                100,
                now,
                MarketDataSource.FILE,
                null
        );

        when(cache.getState(BucketKey.from(historical))).thenReturn(state);
        when(state.getLastUpdate()).thenReturn(now.minusSeconds(10));
        when(state.getHasDataGap()).thenReturn(Map.of());

        MarketDataset snapshot = mock(MarketDataset.class);
        when(manager.snapshot(historical, state)).thenReturn(snapshot);

        engine.getDataset(historical);

        verifyNoInteractions(providerRegistry);
        verify(manager, never()).merge(any(), any(), any(Instant.class));
        verify(manager).snapshot(historical, state);
    }

    @Test
    @DisplayName("Deux requêtes avec un endTime/lookBack différents doivent réutiliser le même MarketDatasetState (même Bucket)")
    void shouldReuseSameStateAcrossRequestsWithDifferentEndTimeAndLookBack() {
        // Régression : la clé de cache ne doit dépendre que du flux natif
        // (symbol + timeFrame + source + providerParam), pas de la fenêtre demandée
        // (endTime, lookBack). On utilise ici un vrai MarketDatasetCache (pas un mock)
        // pour prouver que le comportement réel de mise en cache est correct.
        MarketDatasetCache realCache = new MarketDatasetCache();
        MarketDatasetEngine localEngine = new MarketDatasetEngine(
                realCache, manager, providerRegistry, new TimeFrameConverter(),
                binanceClient, krakenClient, okxClient, assetProviderRepository);

        when(providerRegistry.getProvider(any(), any())).thenReturn(provider);
        when(provider.loadSince(any())).thenReturn(
                new MarketDataset("BTCUSDT", TimeFrame.H1, List.of(), 0, request, now, false)
        );

        MarketDatasetRequest firstCall = new MarketDatasetRequest(
                "BTCUSDT", TimeFrame.H1, 100, now, MarketDataSource.BINANCE, null);
        MarketDatasetRequest secondCall = new MarketDatasetRequest(
                "BTCUSDT", TimeFrame.H1, 30, now.plusSeconds(3600), MarketDataSource.BINANCE, null);

        localEngine.getDataset(firstCall);
        localEngine.getDataset(secondCall);

        // Les deux appels doivent avoir fusionné leurs données dans le MÊME
        // MarketDatasetState (donc le même Bucket sous-jacent), preuve que le Bucket
        // s'accumule dans la durée au lieu de repartir de zéro à chaque endTime différent.
        ArgumentCaptor<MarketDatasetState> mergedStateCaptor = ArgumentCaptor.forClass(MarketDatasetState.class);
        verify(manager, times(2)).merge(mergedStateCaptor.capture(), anyList(), any(Instant.class));

        List<MarketDatasetState> mergedStates = mergedStateCaptor.getAllValues();
        assertSame(mergedStates.get(0), mergedStates.get(1),
                "Les deux requêtes (même symbol/timeFrame/source/providerParam) doivent partager "
                        + "le même MarketDatasetState/Bucket malgré un endTime/lookBack différent");
    }

    /**
     * Cf. docs/etude-fallback-multi-provider-marketdata.md §3 (étape 7d). On isole la logique
     * d'orchestration (boucle de fallback, filtrage horizon) de la logique cache/gap de
     * {@link MarketDatasetEngine#getDataset(MarketDatasetRequest)} — déjà couverte par les tests
     * ci-dessus — via un spy qui stub directement {@code getDataset(...)}.
     */
    @Nested
    @DisplayName("getDatasetForAsset")
    class GetDatasetForAsset {

        MarketDatasetEngine spyEngine;

        @BeforeEach
        void setupSpy() {
            spyEngine = spy(engine);
        }

        private AssetProvider candidate(MarketDataSource source, String providerSymbol, int priority, Integer maxHorizonDays) {
            return AssetProvider.builder()
                    .id((long) priority + (source.ordinal() * 100L))
                    .source(source)
                    .providerSymbol(providerSymbol)
                    .priority(priority)
                    .maxHorizonDays(maxHorizonDays)
                    .build(); // enabled = true par défaut (@Builder.Default)
        }

        @Test
        @DisplayName("Candidat favori répond correctement → aucun appel au candidat suivant")
        void favoriteRespondsCorrectly_noFallback() {
            AssetProvider binance = candidate(MarketDataSource.BINANCE, "BTCUSDT", 0, null);
            AssetProvider kraken = candidate(MarketDataSource.KRAKEN, "XXBTZUSD", 1, 25);
            when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC"))
                    .thenReturn(List.of(binance, kraken));

            MarketDataset expected = mock(MarketDataset.class);
            doReturn(expected).when(spyEngine).getDataset(any());

            MarketDataset result = spyEngine.getDatasetForAsset("BTC", TimeFrame.H1, 100, now);

            assertSame(expected, result);
            verify(spyEngine, times(1)).getDataset(any());
            verify(spyEngine).getDataset(argThat(r ->
                    r.symbol().equals("BTCUSDT") && r.source() == MarketDataSource.BINANCE));
        }

        @Test
        @DisplayName("Candidat favori lève ProviderUnavailableException → bascule sur le candidat priorité 1")
        void favoriteThrowsProviderUnavailable_fallsBackToNextCandidate() {
            AssetProvider binance = candidate(MarketDataSource.BINANCE, "BTCUSDT", 0, null);
            AssetProvider kraken = candidate(MarketDataSource.KRAKEN, "XXBTZUSD", 1, 25);
            when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC"))
                    .thenReturn(List.of(binance, kraken));

            MarketDataset expected = mock(MarketDataset.class);
            doThrow(new ProviderUnavailableException(MarketDataSource.BINANCE, "BTCUSDT", "boom", null))
                    .doReturn(expected)
                    .when(spyEngine).getDataset(any());

            MarketDataset result = spyEngine.getDatasetForAsset("BTC", TimeFrame.H1, 100, now);

            assertSame(expected, result);
            ArgumentCaptor<MarketDatasetRequest> captor = ArgumentCaptor.forClass(MarketDatasetRequest.class);
            verify(spyEngine, times(2)).getDataset(captor.capture());
            assertEquals(MarketDataSource.BINANCE, captor.getAllValues().get(0).source());
            assertEquals(MarketDataSource.KRAKEN, captor.getAllValues().get(1).source());
        }

        @Test
        @DisplayName("Candidat favori lève SymbolNotFoundException → même bascule")
        void favoriteThrowsSymbolNotFound_fallsBackToNextCandidate() {
            AssetProvider binance = candidate(MarketDataSource.BINANCE, "BTCUSDT", 0, null);
            AssetProvider kraken = candidate(MarketDataSource.KRAKEN, "XXBTZUSD", 1, 25);
            when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC"))
                    .thenReturn(List.of(binance, kraken));

            MarketDataset expected = mock(MarketDataset.class);
            doThrow(new SymbolNotFoundException(MarketDataSource.BINANCE, "BTCUSDT", "unknown symbol"))
                    .doReturn(expected)
                    .when(spyEngine).getDataset(any());

            MarketDataset result = spyEngine.getDatasetForAsset("BTC", TimeFrame.H1, 100, now);

            assertSame(expected, result);
            ArgumentCaptor<MarketDatasetRequest> captor = ArgumentCaptor.forClass(MarketDatasetRequest.class);
            verify(spyEngine, times(2)).getDataset(captor.capture());
            assertEquals(MarketDataSource.KRAKEN, captor.getAllValues().get(1).source());
        }

        @Test
        @DisplayName("Tous les candidats échouent → NoProviderAvailableException portant la dernière erreur rencontrée")
        void allCandidatesFail_throwsNoProviderAvailableExceptionWithLastError() {
            AssetProvider binance = candidate(MarketDataSource.BINANCE, "BTCUSDT", 0, null);
            AssetProvider kraken = candidate(MarketDataSource.KRAKEN, "XXBTZUSD", 1, 25);
            when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC"))
                    .thenReturn(List.of(binance, kraken));

            ProviderUnavailableException binanceError =
                    new ProviderUnavailableException(MarketDataSource.BINANCE, "BTCUSDT", "binance down", null);
            SymbolNotFoundException krakenError =
                    new SymbolNotFoundException(MarketDataSource.KRAKEN, "XXBTZUSD", "kraken unknown pair");
            doThrow(binanceError).doThrow(krakenError).when(spyEngine).getDataset(any());

            NoProviderAvailableException thrown = assertThrows(NoProviderAvailableException.class,
                    () -> spyEngine.getDatasetForAsset("BTC", TimeFrame.H1, 100, now));

            assertSame(krakenError, thrown.getLastError());
            verify(spyEngine, times(2)).getDataset(any());
        }

        @Test
        @DisplayName("Aucune ligne asset_provider pour le symbole → NoProviderAvailableException immédiate, aucun appel réseau")
        void noAssetProviderRows_throwsImmediately_noCallAtAll() {
            when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("UNKNOWN"))
                    .thenReturn(List.of());

            NoProviderAvailableException thrown = assertThrows(NoProviderAvailableException.class,
                    () -> spyEngine.getDatasetForAsset("UNKNOWN", TimeFrame.H1, 100, now));

            assertNull(thrown.getLastError());
            verify(spyEngine, never()).getDataset(any());
            verifyNoInteractions(cache, manager, providerRegistry);
        }

        @Test
        @DisplayName("Horizon demandé > maxHorizonDays du favori → favori écarté sans être appelé, bascule directe")
        void horizonExceedsFavoriteMaxHorizon_skipsFavoriteWithoutCallingIt() {
            // Kraken favori (priority 0) mais limité à 30 jours ; requête de 90 jours en D1.
            AssetProvider kraken = candidate(MarketDataSource.KRAKEN, "XXBTZUSD", 0, 30);
            AssetProvider binance = candidate(MarketDataSource.BINANCE, "BTCUSDT", 1, null);
            when(assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC"))
                    .thenReturn(List.of(kraken, binance));

            MarketDataset expected = mock(MarketDataset.class);
            doReturn(expected).when(spyEngine).getDataset(any());

            MarketDataset result = spyEngine.getDatasetForAsset("BTC", TimeFrame.D1, 90, now);

            assertSame(expected, result);
            verify(spyEngine, times(1)).getDataset(any());
            verify(spyEngine).getDataset(argThat(r -> r.source() == MarketDataSource.BINANCE));
        }
    }
}
