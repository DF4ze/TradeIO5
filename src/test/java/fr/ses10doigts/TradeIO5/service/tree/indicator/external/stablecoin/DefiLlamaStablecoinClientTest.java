package fr.ses10doigts.tradeIO5.service.tree.indicator.external.stablecoin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.StablecoinMarketCapResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - DefiLlamaStablecoinClient")
class DefiLlamaStablecoinClientTest {

    // Payload d'exemple conforme à la doc DefiLlama (§7 de l'étude), avec un pegType différent
    // (peggedEUR) volontairement inclus pour vérifier qu'il est bien exclu de la somme.
    private static final String SAMPLE_RESPONSE = """
            {
              "peggedAssets": [
                {
                  "id": "1",
                  "name": "Tether",
                  "symbol": "USDT",
                  "pegType": "peggedUSD",
                  "circulating": { "peggedUSD": 184096473539.0687 },
                  "circulatingPrevDay": { "peggedUSD": 184102908636.32526 },
                  "circulatingPrevWeek": { "peggedUSD": 185005726592.55988 },
                  "circulatingPrevMonth": { "peggedUSD": 187335042280.94324 }
                },
                {
                  "id": "2",
                  "name": "USD Coin",
                  "symbol": "USDC",
                  "pegType": "peggedUSD",
                  "circulating": { "peggedUSD": 30000000000.0 },
                  "circulatingPrevDay": { "peggedUSD": 29000000000.0 },
                  "circulatingPrevWeek": { "peggedUSD": 28000000000.0 },
                  "circulatingPrevMonth": { "peggedUSD": 27000000000.0 }
                },
                {
                  "id": "3",
                  "name": "Stasis Euro",
                  "symbol": "EURS",
                  "pegType": "peggedEUR",
                  "circulating": { "peggedEUR": 500000000.0 },
                  "circulatingPrevDay": { "peggedEUR": 500000000.0 },
                  "circulatingPrevWeek": { "peggedEUR": 500000000.0 },
                  "circulatingPrevMonth": { "peggedEUR": 500000000.0 }
                }
              ]
            }
            """;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("aggregate() ne somme que les pegType peggedUSD")
    void aggregate_sumsOnlyPeggedUsdAssets() throws IOException {
        DefiLlamaStablecoinsRawResponse raw = new ObjectMapper()
                .readValue(SAMPLE_RESPONSE, DefiLlamaStablecoinsRawResponse.class);

        StablecoinMarketCapResponse result = DefiLlamaStablecoinClient.aggregate(raw);

        assertTrue(result.isValid());
        assertEquals(184096473539.0687 + 30000000000.0, result.getTotal(), 0.01);
        assertEquals(184102908636.32526 + 29000000000.0, result.getTotalPrevDay(), 0.01);
        assertEquals(185005726592.55988 + 28000000000.0, result.getTotalPrevWeek(), 0.01);
        assertEquals(187335042280.94324 + 27000000000.0, result.getTotalPrevMonth(), 0.01);
    }

    @Test
    @DisplayName("aggregate() retourne invalid() si peggedAssets est absent")
    void aggregate_returnsInvalid_whenPeggedAssetsMissing() {
        DefiLlamaStablecoinsRawResponse raw = new DefiLlamaStablecoinsRawResponse();

        StablecoinMarketCapResponse result = DefiLlamaStablecoinClient.aggregate(raw);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("fetch() retombe sur invalid() en cas de 500, sans exception")
    void fetch_returnsInvalid_on5xx() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stablecoins", exchange -> {
            byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        DefiLlamaStablecoinClient client = new DefiLlamaStablecoinClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.DEFILLAMA, "", "", "http://127.0.0.1:" + server.getAddress().getPort());

        StablecoinMarketCapResponse result = client.fetch(credential);

        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("fetch() mappe correctement une réponse 200 valide de bout en bout")
    void fetch_mapsValid200Response() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stablecoins", exchange -> {
            byte[] body = SAMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        DefiLlamaStablecoinClient client = new DefiLlamaStablecoinClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.DEFILLAMA, "", "", "http://127.0.0.1:" + server.getAddress().getPort());

        StablecoinMarketCapResponse result = client.fetch(credential);

        assertTrue(result.isValid());
        assertEquals(184096473539.0687 + 30000000000.0, result.getTotal(), 0.01);
    }

    @Test
    @DisplayName("fetch() retombe sur invalid() quand l'hôte est injoignable (pas d'exception)")
    void fetch_returnsInvalid_whenHostUnreachable() {
        DefiLlamaStablecoinClient client = new DefiLlamaStablecoinClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.DEFILLAMA, "", "", "http://127.0.0.1:1");

        StablecoinMarketCapResponse result = client.fetch(credential);

        assertFalse(result.isValid());
    }
}
