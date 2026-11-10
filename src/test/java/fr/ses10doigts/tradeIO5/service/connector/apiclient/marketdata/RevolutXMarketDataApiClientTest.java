package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.RevolutXRequests.Reply;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RevolutXMarketDataApiClientTest {

    private static final String BODY = "{\"data\":[{\"start\":1700086400000,\"open\":\"2\",\"high\":\"3\",\"low\":\"1\",\"close\":\"2.5\",\"volume\":\"10\"},"
            + "{\"start\":1700000000000,\"open\":\"1\",\"high\":\"2\",\"low\":\"0.5\",\"close\":\"1.5\",\"volume\":\"5\"}],\"metadata\":{\"timestamp\":1}}";

    @Test
    void mapsAndSortsCandles() {
        List<MarketData> c = RevolutXMarketDataApiClient.mapCandles(new Reply(200, BODY), "BTC-USDC", TimeFrame.D1);
        assertEquals(2, c.size());
        assertEquals(Instant.ofEpochMilli(1700000000000L), c.getFirst().getTimestamp());
        assertEquals("BTC-USDC", c.getFirst().getPair());
        assertEquals(0, new java.math.BigDecimal("1.5").compareTo(c.getFirst().getClose()));
    }

    @Test
    void emptyDataIsAnEmptyList() {
        assertTrue(RevolutXMarketDataApiClient.mapCandles(new Reply(200, "{\"data\":[]}"), "BTC-USDC", TimeFrame.H1).isEmpty());
    }

    @Test
    void errorStatusesAreClassified() {
        assertThrows(SymbolNotFoundException.class, () -> RevolutXMarketDataApiClient.mapCandles(new Reply(404, "{\"message\":\"nope\"}"), "X", TimeFrame.H1));
        assertThrows(ProviderUnavailableException.class, () -> RevolutXMarketDataApiClient.mapCandles(new Reply(429, ""), "X", TimeFrame.H1));
        assertThrows(ProviderUnavailableException.class, () -> RevolutXMarketDataApiClient.mapCandles(new Reply(200, "pas du json"), "X", TimeFrame.H1));
    }

    @Test
    void windowIsBoundedByPageSizeAndUntil() {
        Instant now = Instant.parse("2026-01-10T00:00:00Z");
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        long[] w = RevolutXMarketDataApiClient.window(start, null, 5, 1440, now);
        assertEquals(start.toEpochMilli(), w[0]);
        assertEquals(start.plusSeconds(5 * 86400L).toEpochMilli(), w[1]);
        Instant until = Instant.parse("2026-01-03T00:00:00Z");
        assertEquals(until.toEpochMilli(), RevolutXMarketDataApiClient.window(start, until, 5, 1440, now)[1]);
        long[] back = RevolutXMarketDataApiClient.window(null, until, 2, 1440, now);
        assertEquals(until.minusSeconds(2 * 86400L).toEpochMilli(), back[0]);
    }

    @Test
    void onlyH1AndD1AreSupported() {
        assertEquals(60, RevolutXMarketDataApiClient.nativeInterval(TimeFrame.H1));
        assertEquals(1440, RevolutXMarketDataApiClient.nativeInterval(TimeFrame.D1));
        assertThrows(IllegalArgumentException.class, () -> RevolutXMarketDataApiClient.nativeInterval(TimeFrame.H4));
    }
}
