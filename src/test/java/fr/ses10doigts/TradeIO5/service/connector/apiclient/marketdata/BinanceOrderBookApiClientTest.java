package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("MarketDataApiClient - Binance order book")
class BinanceOrderBookApiClientTest {

    // Payload d'exemple issu de la doc Binance GET /api/v3/depth
    private static final String SAMPLE_RESPONSE = """
            {
              "lastUpdateId": 1027024,
              "bids": [
                ["4.00000000", "431.00000000"],
                ["3.99000000", "9.00000000"]
              ],
              "asks": [
                ["4.00000200", "12.00000000"],
                ["4.01000000", "5.00000000"]
              ]
            }
            """;

    @Test
    void mapDepthResponse_mapsBidsAndAsks() {
        OrderBookSnapshot snapshot = BinanceOrderBookApiClient.mapDepthResponse(SAMPLE_RESPONSE);

        assertEquals(2, snapshot.bids().size());
        assertEquals(2, snapshot.asks().size());

        assertEquals(0, new BigDecimal("4.00000000").compareTo(snapshot.bids().getFirst().price()));
        assertEquals(0, new BigDecimal("431.00000000").compareTo(snapshot.bids().getFirst().quantity()));

        assertEquals(0, new BigDecimal("4.00000200").compareTo(snapshot.asks().getFirst().price()));
        assertEquals(0, new BigDecimal("12.00000000").compareTo(snapshot.asks().getFirst().quantity()));
    }
}
