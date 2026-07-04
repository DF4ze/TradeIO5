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

@DisplayName("MarketDataApiClient - OKX")
class OkxMarketDataApiClientTest {

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
    void mapCandlesResponse_throwsOnOkxError() {
        assertThrows(IllegalStateException.class,
                () -> OkxMarketDataApiClient.mapCandlesResponse(ERROR_RESPONSE, "BTC-USDT", TimeFrame.H1));
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
}
