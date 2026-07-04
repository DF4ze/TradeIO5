package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MarketDataApiClient - Kraken")
class KrakenMarketDataApiClientTest {

    // Payload d'exemple issu de la doc Kraken GET /0/public/OHLC
    private static final String SAMPLE_RESPONSE = """
            {
              "error": [],
              "result": {
                "XXBTZUSD": [
                  [1616662350, "52063.7", "52200.0", "51900.0", "52100.5", "52050.3", "12.34567800", 42]
                ],
                "last": 1616662350
              }
            }
            """;

    private static final String ERROR_RESPONSE = """
            {
              "error": ["EQuery:Unknown asset pair"],
              "result": {}
            }
            """;

    @Test
    void mapOhlcResponse_mapsKrakenPairArraysToMarketData() throws Exception {
        List<MarketData> candles = KrakenMarketDataApiClient.mapOhlcResponse(SAMPLE_RESPONSE, "XBTUSD", TimeFrame.H1);

        assertEquals(1, candles.size());

        MarketData candle = candles.get(0);
        assertEquals("XBTUSD", candle.getPair());
        assertEquals(TimeFrame.H1, candle.getTimeFrame());
        assertEquals(Instant.ofEpochSecond(1616662350), candle.getTimestamp());
        assertEquals(0, new BigDecimal("52063.7").compareTo(candle.getOpen()));
        assertEquals(0, new BigDecimal("52200.0").compareTo(candle.getHigh()));
        assertEquals(0, new BigDecimal("51900.0").compareTo(candle.getLow()));
        assertEquals(0, new BigDecimal("52100.5").compareTo(candle.getClose()));
        assertEquals(0, new BigDecimal("12.34567800").compareTo(candle.getVolume()));
    }

    @Test
    void mapOhlcResponse_throwsOnKrakenError() {
        Exception ex = assertThrows(IllegalStateException.class,
                () -> KrakenMarketDataApiClient.mapOhlcResponse(ERROR_RESPONSE, "XBTUSD", TimeFrame.H1));
        assertTrue(ex.getMessage().contains("Kraken API error"));
    }

    @Test
    void nativeInterval_returnsKrakenMinutesForH1() {
        assertEquals(60, KrakenMarketDataApiClient.nativeInterval(TimeFrame.H1));
    }

    @Test
    void nativeInterval_throwsForUnsupportedTimeFrame() {
        assertThrows(IllegalArgumentException.class,
                () -> KrakenMarketDataApiClient.nativeInterval(TimeFrame.H4));
    }
}
