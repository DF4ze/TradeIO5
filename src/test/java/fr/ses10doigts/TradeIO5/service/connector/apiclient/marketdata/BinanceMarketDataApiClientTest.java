package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MarketDataApiClient - Binance")
class BinanceMarketDataApiClientTest extends AbstractMarketDataApiClientContractTest {

    @Override
    protected MarketDataSource expectedSource() {
        return MarketDataSource.BINANCE;
    }

    @Override
    protected MarketDataProviderException triggerSymbolNotFound() {
        BinanceClientException e = new BinanceClientException("Invalid symbol.", "Invalid symbol.", 400, -1121);
        return BinanceMarketDataApiClient.mapError("BADUSDT", e);
    }

    @Override
    protected MarketDataProviderException triggerProviderUnavailable() {
        BinanceConnectorException e = new BinanceConnectorException("Connection reset");
        return BinanceMarketDataApiClient.mapError("BTCUSDT", e);
    }

    // Payload d'exemple issu de la doc Binance GET /api/v3/klines
    private static final String SAMPLE_RESPONSE = """
            [
              [
                1499040000000,
                "0.01634790",
                "0.80000000",
                "0.01575800",
                "0.01577100",
                "148976.11427815",
                1499644799999,
                "2434.19055334",
                308,
                "1756.87402397",
                "28.46694368",
                "17928899.62484339"
              ]
            ]
            """;

    @Test
    void mapKlinesResponse_mapsBinancePositionalArrayToMarketData() {
        List<MarketData> candles = BinanceMarketDataApiClient.mapKlinesResponse(SAMPLE_RESPONSE, "BTCUSDT", TimeFrame.H1);

        assertEquals(1, candles.size());

        MarketData candle = candles.get(0);
        assertEquals("BTCUSDT", candle.getPair());
        assertEquals(TimeFrame.H1, candle.getTimeFrame());
        assertEquals(Instant.ofEpochMilli(1499040000000L), candle.getTimestamp());
        assertEquals(0, new BigDecimal("0.01634790").compareTo(candle.getOpen()));
        assertEquals(0, new BigDecimal("0.80000000").compareTo(candle.getHigh()));
        assertEquals(0, new BigDecimal("0.01575800").compareTo(candle.getLow()));
        assertEquals(0, new BigDecimal("0.01577100").compareTo(candle.getClose()));
        assertEquals(0, new BigDecimal("148976.11427815").compareTo(candle.getVolume()));
    }

    @Test
    void nativeInterval_returnsBinanceCodeForH1() {
        assertEquals("1h", BinanceMarketDataApiClient.nativeInterval(TimeFrame.H1));
    }

    @Test
    void nativeInterval_throwsForUnsupportedTimeFrame() {
        assertThrows(IllegalArgumentException.class,
                () -> BinanceMarketDataApiClient.nativeInterval(TimeFrame.D1));
    }

    @Test
    void mapError_invalidSymbolCode_returnsSymbolNotFoundException() {
        BinanceClientException e = new BinanceClientException(
                "Invalid symbol.", "Invalid symbol.", 400, -1121);

        MarketDataProviderException ex = BinanceMarketDataApiClient.mapError("BADUSDT", e);

        assertInstanceOf(SymbolNotFoundException.class, ex);
        assertEquals(MarketDataSource.BINANCE, ex.getSource());
        assertEquals("BADUSDT", ex.getSymbol());
    }

    @Test
    void mapError_otherClientException_returnsProviderUnavailableException() {
        BinanceClientException e = new BinanceClientException(
                "Too many requests.", "Too many requests.", 429, -1003);

        MarketDataProviderException ex = BinanceMarketDataApiClient.mapError("BTCUSDT", e);

        assertInstanceOf(ProviderUnavailableException.class, ex);
        assertEquals(MarketDataSource.BINANCE, ex.getSource());
    }

    @Test
    void mapError_connectorException_returnsProviderUnavailableException() {
        BinanceConnectorException e = new BinanceConnectorException("Connection reset");

        MarketDataProviderException ex = BinanceMarketDataApiClient.mapError("BTCUSDT", e);

        assertInstanceOf(ProviderUnavailableException.class, ex);
        assertEquals(MarketDataSource.BINANCE, ex.getSource());
    }

    @Test
    void mapError_unexpectedException_returnsProviderUnavailableException() {
        RuntimeException e = new RuntimeException("boom");

        MarketDataProviderException ex = BinanceMarketDataApiClient.mapError("BTCUSDT", e);

        assertInstanceOf(ProviderUnavailableException.class, ex);
        assertEquals(MarketDataSource.BINANCE, ex.getSource());
    }
}
