package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MarketDataApiClient - OKX")
class OkxMarketDataApiClientTest extends AbstractMarketDataApiClientContractTest {

    @Override
    protected MarketDataSource expectedSource() {
        return MarketDataSource.OKX;
    }

    @Override
    protected MarketDataProviderException triggerSymbolNotFound() {
        return assertThrows(SymbolNotFoundException.class,
                () -> OkxMarketDataApiClient.mapCandlesResponse(ERROR_RESPONSE, "BTC-USDT", TimeFrame.H1));
    }

    @Override
    protected MarketDataProviderException triggerProviderUnavailable() {
        return assertThrows(ProviderUnavailableException.class,
                () -> OkxMarketDataApiClient.mapCandlesResponse(RATE_LIMIT_RESPONSE, "BTC-USDT", TimeFrame.H1));
    }

    // Payload d'exemple issu de la doc OKX GET /api/v5/market/candles
    private static final String SAMPLE_RESPONSE = """
            {
              "code": "0",
              "msg": "",
              "data": [
                ["1597026383085", "3.721", "3.743", "3.677", "3.708", "8422410", "22698348.04", "22698348.04", "1"]
              ]
            }
            """;

    private static final String ERROR_RESPONSE = """
            {
              "code": "51001",
              "msg": "Instrument ID does not exist",
              "data": []
            }
            """;

    private static final String RATE_LIMIT_RESPONSE = """
            {
              "code": "50011",
              "msg": "Requests too frequent",
              "data": []
            }
            """;

    @Test
    void mapCandlesResponse_mapsOkxArrayToMarketData() throws Exception {
        List<MarketData> candles = OkxMarketDataApiClient.mapCandlesResponse(SAMPLE_RESPONSE, "BTC-USDT", TimeFrame.H1);

        assertEquals(1, candles.size());

        MarketData candle = candles.get(0);
        assertEquals("BTC-USDT", candle.getPair());
        assertEquals(TimeFrame.H1, candle.getTimeFrame());
        assertEquals(Instant.ofEpochMilli(1597026383085L), candle.getTimestamp());
        assertEquals(0, new BigDecimal("3.721").compareTo(candle.getOpen()));
        assertEquals(0, new BigDecimal("3.743").compareTo(candle.getHigh()));
        assertEquals(0, new BigDecimal("3.677").compareTo(candle.getLow()));
        assertEquals(0, new BigDecimal("3.708").compareTo(candle.getClose()));
        assertEquals(0, new BigDecimal("8422410").compareTo(candle.getVolume()));
    }

    @Test
    void mapCandlesResponse_throwsSymbolNotFoundOnInstrumentNotFound() {
        SymbolNotFoundException ex = assertThrows(SymbolNotFoundException.class,
                () -> OkxMarketDataApiClient.mapCandlesResponse(ERROR_RESPONSE, "BTC-USDT", TimeFrame.H1));
        assertEquals(MarketDataSource.OKX, ex.getSource());
        assertEquals("BTC-USDT", ex.getSymbol());
    }

    @Test
    void mapCandlesResponse_throwsProviderUnavailableOnOtherErrorCode() {
        ProviderUnavailableException ex = assertThrows(ProviderUnavailableException.class,
                () -> OkxMarketDataApiClient.mapCandlesResponse(RATE_LIMIT_RESPONSE, "BTC-USDT", TimeFrame.H1));
        assertEquals(MarketDataSource.OKX, ex.getSource());
    }

    @Test
    void nativeBar_returnsOkxCodeForH1() {
        assertEquals("1H", OkxMarketDataApiClient.nativeBar(TimeFrame.H1));
    }

    @Test
    void nativeBar_throwsForUnsupportedTimeFrame() {
        assertThrows(IllegalArgumentException.class,
                () -> OkxMarketDataApiClient.nativeBar(TimeFrame.MIN5));
    }

    // --- mergeWithHistory (routing history-candles, étape 2b) ---

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    private static MarketData candleAt(Instant ts) {
        return MarketData.builder()
                .pair("BTC-USDT")
                .timeFrame(TimeFrame.H1)
                .timestamp(ts)
                .open(BigDecimal.ONE)
                .high(BigDecimal.ONE)
                .low(BigDecimal.ONE)
                .close(BigDecimal.ONE)
                .volume(BigDecimal.ONE)
                .build();
    }

    private static List<MarketData> hourlyCandles(int fromHoursAgo, int toHoursAgoInclusive) {
        List<MarketData> candles = new java.util.ArrayList<>();
        for (int h = fromHoursAgo; h >= toHoursAgoInclusive; h--) {
            candles.add(candleAt(NOW.minus(h, ChronoUnit.HOURS)));
        }
        return candles;
    }

    @Test
    void mergeWithHistory_paginatesToHistoryCandlesWhenGapRemains() {
        // "recent" ne couvre que les 10 dernières heures (90h..99h avant NOW, cf. hourlyCandles)
        List<MarketData> recent = hourlyCandles(99, 90);
        Instant since = NOW.minus(99, ChronoUnit.HOURS).minus(10, ChronoUnit.HOURS); // 109h avant NOW

        int[] callCount = {0};
        Function<Instant, List<MarketData>> historyFetcher = oldest -> {
            callCount[0]++;
            // Une seule page suffit à couvrir since : 91h..109h avant l'ancienne borne (90h)
            return hourlyCandles(109, 91);
        };

        List<MarketData> merged = OkxMarketDataApiClient.mergeWithHistory(recent, since, historyFetcher);

        assertEquals(1, callCount[0]);
        Instant oldestMerged = merged.stream().map(MarketData::getTimestamp).min(Instant::compareTo).orElseThrow();
        assertFalse(oldestMerged.isAfter(since));
        assertEquals(recent.size() + 19, merged.size());
    }

    @Test
    void mergeWithHistory_stopsWithoutExceptionWhenHistoryPageIsEmpty() {
        List<MarketData> recent = hourlyCandles(99, 90);
        Instant since = NOW.minus(500, ChronoUnit.HOURS); // bien plus loin que ce que l'historique couvre

        Function<Instant, List<MarketData>> historyFetcher = oldest -> List.of();

        List<MarketData> merged = OkxMarketDataApiClient.mergeWithHistory(recent, since, historyFetcher);

        assertEquals(recent.size(), merged.size());
    }

    @Test
    void mergeWithHistory_neverCallsHistoryFetcherWhenSinceIsNull() {
        List<MarketData> recent = hourlyCandles(99, 90);

        Function<Instant, List<MarketData>> historyFetcher = oldest -> {
            throw new AssertionError("history-candles ne doit jamais être appelé quand since == null");
        };

        List<MarketData> merged = OkxMarketDataApiClient.mergeWithHistory(recent, null, historyFetcher);

        assertEquals(recent, merged);
    }
}
