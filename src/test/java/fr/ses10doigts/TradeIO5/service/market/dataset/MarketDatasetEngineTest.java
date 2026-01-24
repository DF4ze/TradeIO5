package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProvider;
import fr.ses10doigts.tradeIO5.service.market.provider.MarketDataProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    MarketDatasetEngine engine;

    MarketDatasetRequest request;
    Instant now;

    @Autowired
    private MarketDatasetEngine marketDatasetEngine;


    @BeforeEach
    void setup() {
        now = Instant.now();

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
    void shouldFetchWhenLastUpdateIsNull() {
        when(cache.getState(any(MarketDatasetRequest.class)))
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
        verify(manager).merge(eq(state), anyList());
        verify(manager).snapshot(request, state);
        assertSame(snapshot, result);
    }

    @Test
    void shouldNotFetchWhenCacheIsFreshForLiveSource() {
        when(cache.getState(request)).thenReturn(state);
        when(state.getLastUpdate()).thenReturn(now.minusSeconds(10));
        when(state.getHasDataGap()).thenReturn(Map.of());

        MarketDataset snapshot = mock(MarketDataset.class);
        when(manager.snapshot(request, state)).thenReturn(snapshot);

        MarketDataset result = engine.getDataset(request);

        verifyNoInteractions(providerRegistry);
        verify(manager, never()).merge(any(), any());
        verify(manager).snapshot(request, state);
        assertSame(snapshot, result);
    }

    @Test
    void shouldFillMissingDataWhenGapExists() {
        when(cache.getState(request)).thenReturn(state);
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

        verify(manager, atLeastOnce()).merge(eq(state), anyList());
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

        when(cache.getState(historical)).thenReturn(state);
        when(state.getLastUpdate()).thenReturn(now.minusSeconds(10));
        when(state.getHasDataGap()).thenReturn(Map.of());

        MarketDataset snapshot = mock(MarketDataset.class);
        when(manager.snapshot(historical, state)).thenReturn(snapshot);

        engine.getDataset(historical);

        verifyNoInteractions(providerRegistry);
        verify(manager, never()).merge(any(), any());
        verify(manager).snapshot(historical, state);
    }
}