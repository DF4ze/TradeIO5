package fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.FundingRateResponse;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.LiquidationHistoryResponse;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.OpenInterestHistoryResponse;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.OpenInterestResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - CoinalyzeClient")
class CoinalyzeClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ApiCredentialDTO credentialFor(HttpServer srv) {
        return new ApiCredentialDTO(WebProviderCode.COINALYZE, "test-key", "", "http://127.0.0.1:" + srv.getAddress().getPort());
    }

    @Test
    @DisplayName("fetchOpenInterest() mappe correctement une réponse 200")
    void fetchOpenInterest_mapsValidResponse() throws IOException {
        server = startServer("/open-interest", 200,
                "[{\"symbol\":\"BTCUSDT_PERP.A\",\"value\":123456.78,\"update\":1720000000000}]");

        CoinalyzeClient client = new CoinalyzeClient();
        OpenInterestResponse response = client.fetchOpenInterest(credentialFor(server), "BTCUSDT_PERP.A");

        assertTrue(response.isValid());
        assertEquals(1, response.getEntries().size());
        assertEquals("BTCUSDT_PERP.A", response.getEntries().getFirst().getSymbol());
        assertEquals(123456.78, response.getEntries().getFirst().getValue(), 0.001);
    }

    @Test
    @DisplayName("fetchOpenInterest() retombe sur invalid() sur 500, sans exception")
    void fetchOpenInterest_returnsInvalid_on5xx() throws IOException {
        server = startServer("/open-interest", 500, "boom");

        CoinalyzeClient client = new CoinalyzeClient();
        OpenInterestResponse response = client.fetchOpenInterest(credentialFor(server), "BTCUSDT_PERP.A");

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("fetchFundingRate() mappe correctement une réponse 200")
    void fetchFundingRate_mapsValidResponse() throws IOException {
        server = startServer("/funding-rate", 200,
                "[{\"symbol\":\"BTCUSDT_PERP.A\",\"value\":0.0001,\"update\":1720000000000}]");

        CoinalyzeClient client = new CoinalyzeClient();
        FundingRateResponse response = client.fetchFundingRate(credentialFor(server), "BTCUSDT_PERP.A");

        assertTrue(response.isValid());
        assertEquals(0.0001, response.getEntries().getFirst().getValue(), 0.00001);
    }

    @Test
    @DisplayName("fetchFundingRate() retombe sur invalid() sur 4xx, sans exception")
    void fetchFundingRate_returnsInvalid_on4xx() throws IOException {
        server = startServer("/funding-rate", 400, "bad request");

        CoinalyzeClient client = new CoinalyzeClient();
        FundingRateResponse response = client.fetchFundingRate(credentialFor(server), "BTCUSDT_PERP.A");

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("fetchOpenInterestHistory() mappe correctement l'historique 200 (forme candle o/h/l/c)")
    void fetchOpenInterestHistory_mapsValidResponse() throws IOException {
        server = startServer("/open-interest-history", 200,
                "[{\"symbol\":\"BTCUSDT_PERP.A\",\"history\":["
                        + "{\"t\":1720000000,\"o\":100.0,\"h\":110.0,\"l\":95.0,\"c\":105.0},"
                        + "{\"t\":1720003600,\"o\":105.0,\"h\":120.0,\"l\":100.0,\"c\":118.0}"
                        + "]}]");

        CoinalyzeClient client = new CoinalyzeClient();
        Instant to = Instant.now();
        Instant from = to.minus(2, ChronoUnit.HOURS);
        OpenInterestHistoryResponse response = client.fetchOpenInterestHistory(credentialFor(server), "BTCUSDT_PERP.A", from, to, "1hour");

        assertTrue(response.isValid());
        assertEquals(1, response.getEntries().size());
        assertEquals(2, response.getEntries().getFirst().getHistory().size());
        assertEquals(105.0, response.getEntries().getFirst().getHistory().getFirst().getC(), 0.001);
        assertEquals(118.0, response.getEntries().getFirst().getHistory().get(1).getC(), 0.001);
    }

    @Test
    @DisplayName("fetchOpenInterestHistory() retombe sur invalid() sur 500, sans exception")
    void fetchOpenInterestHistory_returnsInvalid_on5xx() throws IOException {
        server = startServer("/open-interest-history", 500, "boom");

        CoinalyzeClient client = new CoinalyzeClient();
        Instant to = Instant.now();
        Instant from = to.minus(2, ChronoUnit.HOURS);
        OpenInterestHistoryResponse response = client.fetchOpenInterestHistory(credentialFor(server), "BTCUSDT_PERP.A", from, to, "1hour");

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("fetchLiquidations() mappe correctement l'historique 200")
    void fetchLiquidations_mapsValidResponse() throws IOException {
        server = startServer("/liquidation-history", 200,
                "[{\"symbol\":\"BTCUSDT_PERP.A\",\"history\":["
                        + "{\"t\":1720000000,\"l\":10.5,\"s\":3.2},"
                        + "{\"t\":1720003600,\"l\":1.1,\"s\":0.4}"
                        + "]}]");

        CoinalyzeClient client = new CoinalyzeClient();
        Instant to = Instant.now();
        Instant from = to.minus(24, ChronoUnit.HOURS);
        LiquidationHistoryResponse response = client.fetchLiquidations(credentialFor(server), "BTCUSDT_PERP.A", from, to, "1hour");

        assertTrue(response.isValid());
        assertEquals(1, response.getEntries().size());
        assertEquals(2, response.getEntries().getFirst().getHistory().size());
        assertEquals(10.5, response.getEntries().getFirst().getHistory().getFirst().getL(), 0.001);
        assertEquals(3.2, response.getEntries().getFirst().getHistory().getFirst().getS(), 0.001);
    }

    @Test
    @DisplayName("fetchLiquidations() retombe sur invalid() quand l'hôte est injoignable")
    void fetchLiquidations_returnsInvalid_whenHostUnreachable() {
        CoinalyzeClient client = new CoinalyzeClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.COINALYZE, "k", "", "http://127.0.0.1:1");

        Instant to = Instant.now();
        Instant from = to.minus(24, ChronoUnit.HOURS);
        LiquidationHistoryResponse response = client.fetchLiquidations(credential, "BTCUSDT_PERP.A", from, to, "1hour");

        assertFalse(response.isValid());
    }

    private HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        srv.start();
        return srv;
    }
}
