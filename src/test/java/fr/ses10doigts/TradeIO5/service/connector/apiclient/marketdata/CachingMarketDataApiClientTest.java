package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.market.CandleEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.market.CandleRepository;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. docs/etude-cache-db-candles-h1.md. Vérifie le coeur du décorateur :
 * - lecture-avant-réseau (aucun appel délégué si tout est déjà en cache) ;
 * - détection des trous et appel réseau borné à ces trous uniquement ;
 * - non-persistance de la bougie en cours (section 5) ;
 * - tolérance aux violations de contrainte unique en écriture concurrente (section 6).
 */
@DisplayName("CachingMarketDataApiClient")
class CachingMarketDataApiClientTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z"); // aligné grille H1

    private CandleRepository repository;
    private MarketDataApiClient delegate;
    private FixedDomainClock clock;
    private CachingMarketDataApiClient client;

    @BeforeEach
    void setUp() {
        repository = mock(CandleRepository.class);
        delegate = mock(MarketDataApiClient.class);
        when(delegate.getSource()).thenReturn(MarketDataSource.BINANCE);
        clock = new FixedDomainClock(T0.plus(java.time.Duration.ofDays(1)));
        client = new CachingMarketDataApiClient(delegate, repository, clock);
    }

    @Test
    @DisplayName("getSource() délègue simplement")
    void getSource_delegates() {
        assertEquals(MarketDataSource.BINANCE, client.getSource());
    }

    @Test
    @DisplayName("Plage entièrement en cache : aucun appel réseau")
    void getCandles_fullyCached_doesNotCallDelegate() {
        Instant since = T0;
        Instant until = T0.plusSeconds(3600 * 2);

        when(repository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                eq(MarketDataSource.BINANCE), eq(SYMBOL), eq(TimeFrame.H1), eq(since), eq(until)))
                .thenReturn(List.of(
                        entity(since),
                        entity(since.plusSeconds(3600)),
                        entity(since.plusSeconds(7200))
                ));

        List<MarketData> result = client.getCandles(SYMBOL, TimeFrame.H1, since, until, 0);

        assertEquals(3, result.size());
        verify(delegate, never()).getCandles(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Trou au milieu de la plage : le réseau n'est appelé que pour ce trou")
    void getCandles_gapInMiddle_onlyFetchesTheGap() {
        Instant since = T0;
        Instant middle = T0.plusSeconds(3600);
        Instant until = T0.plusSeconds(7200);

        when(repository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                eq(MarketDataSource.BINANCE), eq(SYMBOL), eq(TimeFrame.H1), eq(since), eq(until)))
                .thenReturn(List.of(entity(since), entity(until))); // "middle" manquant

        MarketData fetchedMiddle = candle(middle);
        when(delegate.getCandles(SYMBOL, TimeFrame.H1, middle, middle, 1))
                .thenReturn(List.of(fetchedMiddle));

        List<MarketData> result = client.getCandles(SYMBOL, TimeFrame.H1, since, until, 0);

        assertEquals(3, result.size());
        assertEquals(List.of(since, middle, until),
                result.stream().map(MarketData::getTimestamp).toList());

        verify(delegate, times(1)).getCandles(SYMBOL, TimeFrame.H1, middle, middle, 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandleEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository, times(1)).saveAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(middle, captor.getValue().get(0).getTimestamp());
    }

    @Test
    @DisplayName("La bougie en cours (non fermée) n'est jamais persistée, mais reste renvoyée")
    void getCandles_unclosedTailCandle_isNeverPersistedButStillReturned() {
        Instant now = T0.plusSeconds(1800); // 30 min après l'ouverture de la bougie H1 en cours
        clock.set(now);

        Instant currentOpen = T0; // bougie [T0, T0+1h) : pas encore fermée à "now"

        when(repository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                eq(MarketDataSource.BINANCE), eq(SYMBOL), eq(TimeFrame.H1), eq(currentOpen), eq(currentOpen)))
                .thenReturn(List.of());

        MarketData unclosed = candle(currentOpen);
        when(delegate.getCandles(SYMBOL, TimeFrame.H1, currentOpen, currentOpen, 1))
                .thenReturn(List.of(unclosed));

        List<MarketData> result = client.getCandles(SYMBOL, TimeFrame.H1, null, now, 1);

        assertEquals(1, result.size());
        assertEquals(currentOpen, result.get(0).getTimestamp());
        verify(repository, never()).saveAll(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("since=null : la plage est dérivée de until/limit")
    void getCandles_nullSince_derivesRangeFromLimit() {
        Instant until = T0.plusSeconds(7200); // grid-aligned
        Instant since = T0.plusSeconds(3600); // until - (limit-1)*1h avec limit=2

        when(repository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                eq(MarketDataSource.BINANCE), eq(SYMBOL), eq(TimeFrame.H1), eq(since), eq(until)))
                .thenReturn(List.of(entity(since), entity(until)));

        List<MarketData> result = client.getCandles(SYMBOL, TimeFrame.H1, null, until, 2);

        assertEquals(2, result.size());
        verify(delegate, never()).getCandles(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Violation de contrainte unique en écriture batch : repli ligne à ligne, doublons ignorés")
    void getCandles_uniqueConstraintViolationOnBatchInsert_fallsBackRowByRow() {
        Instant since = T0;
        Instant middle1 = T0.plusSeconds(3600);
        Instant middle2 = T0.plusSeconds(7200);
        Instant until = T0.plusSeconds(10800);

        when(repository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                eq(MarketDataSource.BINANCE), eq(SYMBOL), eq(TimeFrame.H1), eq(since), eq(until)))
                .thenReturn(List.of(entity(since), entity(until)));

        when(delegate.getCandles(SYMBOL, TimeFrame.H1, middle1, middle2, 2))
                .thenReturn(List.of(candle(middle1), candle(middle2)));

        when(repository.saveAll(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        List<MarketData> result = client.getCandles(SYMBOL, TimeFrame.H1, since, until, 0);

        assertEquals(4, result.size());
        verify(repository, times(2)).saveAndFlush(any(CandleEntity.class));
    }

    @Test
    @DisplayName("isClosed : close <= now => fermée, sinon en cours")
    void isClosed_behavesAroundCloseBoundary() {
        MarketData candle = candle(T0);
        assertTrue(CachingMarketDataApiClient.isClosed(candle, TimeFrame.H1, T0.plusSeconds(3600))); // == close
        assertTrue(CachingMarketDataApiClient.isClosed(candle, TimeFrame.H1, T0.plusSeconds(3601))); // > close
        assertFalse(CachingMarketDataApiClient.isClosed(candle, TimeFrame.H1, T0.plusSeconds(3599))); // < close
    }

    @Test
    @DisplayName("floorToGrid aligne sur le multiple de la durée du TimeFrame")
    void floorToGrid_alignsOnStep() {
        Instant unaligned = T0.plusSeconds(3661);
        assertEquals(T0.plusSeconds(3600), CachingMarketDataApiClient.floorToGrid(unaligned, TimeFrame.H1));
        assertEquals(T0, CachingMarketDataApiClient.floorToGrid(T0, TimeFrame.H1));
    }

    @Test
    @DisplayName("findGaps détecte un trou au début, au milieu et à la fin")
    void findGaps_detectsGapsAtStartMiddleAndEnd() {
        Instant t0 = T0;
        Instant t1 = T0.plusSeconds(3600);
        Instant t2 = T0.plusSeconds(7200);
        Instant t3 = T0.plusSeconds(10800);
        Instant t4 = T0.plusSeconds(14400);

        // Cache ne contient que t2 : trou [t0,t1] et trou [t3,t4]
        List<MarketData> cached = List.of(candle(t2));

        List<CachingMarketDataApiClient.Range> gaps =
                CachingMarketDataApiClient.findGaps(cached, t0, t4, TimeFrame.H1);

        assertEquals(2, gaps.size());
        assertEquals(t0, gaps.get(0).since());
        assertEquals(t1, gaps.get(0).until());
        assertEquals(t3, gaps.get(1).since());
        assertEquals(t4, gaps.get(1).until());
    }

    private static CandleEntity entity(Instant timestamp) {
        return CandleEntity.builder()
                .source(MarketDataSource.BINANCE)
                .pair(SYMBOL)
                .timeFrame(TimeFrame.H1)
                .timestamp(timestamp)
                .open(BigDecimal.ONE)
                .high(BigDecimal.ONE)
                .low(BigDecimal.ONE)
                .close(BigDecimal.ONE)
                .volume(BigDecimal.ONE)
                .build();
    }

    private static MarketData candle(Instant timestamp) {
        return MarketData.builder()
                .pair(SYMBOL)
                .timeFrame(TimeFrame.H1)
                .timestamp(timestamp)
                .open(BigDecimal.ONE)
                .high(BigDecimal.ONE)
                .low(BigDecimal.ONE)
                .close(BigDecimal.ONE)
                .volume(BigDecimal.ONE)
                .build();
    }
}
