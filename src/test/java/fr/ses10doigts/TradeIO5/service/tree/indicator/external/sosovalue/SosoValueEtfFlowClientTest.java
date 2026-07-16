package fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixtures inline (pas de fichier {@code src/test/resources/fixtures/}, contrairement à
 * {@code FarsideEtfFlowClientTest}) : le corps JSON est court. Enveloppe
 * {@code {"code":0,"message":"success","data":[...]}} confirmée contre un appel réel le
 * 2026-07-16 (clé Clem) — voir avertissement de classe {@code SosoValueEtfFlowClient}, corrigé
 * après un premier échec en prod ({@code got OBJECT} au lieu du tableau brut suggéré par l'exemple
 * de la doc endpoint-spécifique).
 */
@DisplayName("Indicator External - SosoValueEtfFlowClient")
class SosoValueEtfFlowClientTest {

    private static final String VALID_BODY = """
            {
                "code": 0,
                "message": "success",
                "data": [
                    {
                        "date": "2026-07-15",
                        "total_net_inflow": -55066297.0,
                        "total_value_traded": 4706120449.0,
                        "total_net_assets": 56216535367.0,
                        "cum_net_inflow": 13534833596.095
                    },
                    {
                        "date": "2026-07-14",
                        "total_net_inflow": 91269283.0,
                        "total_value_traded": 2498627928.0,
                        "total_net_assets": 59225065270.0,
                        "cum_net_inflow": 12581438056.095
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
    @DisplayName("parse() extrait la première ligne de 'data' (déjà triée du plus récent au plus ancien)")
    void parse_validBody_extractsFirstRow() {
        EtfFlowResponse response = SosoValueEtfFlowClient.parse(VALID_BODY, EtfFlowAsset.BTC);

        assertTrue(response.isValid());
        assertEquals(LocalDate.of(2026, 7, 15), response.getDate());
        assertEquals(-55066297.0, response.getTotal(), 0.001);
    }

    @Test
    @DisplayName("parse() n'expose jamais de détail par émetteur (choix délibéré, cf. javadoc de classe)")
    void parse_neverExposesByIssuer() {
        EtfFlowResponse response = SosoValueEtfFlowClient.parse(VALID_BODY, EtfFlowAsset.BTC);

        assertTrue(response.isValid());
        assertTrue(response.getByIssuer().isEmpty());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() sur 'data' vide, sans exception")
    void parse_returnsInvalid_whenEmptyDataArray() {
        String body = """
                {"code": 0, "message": "success", "data": []}
                """;

        EtfFlowResponse response = SosoValueEtfFlowClient.parse(body, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() sur un tableau brut sans enveloppe (régression du bug initial)")
    void parse_returnsInvalid_whenBareArrayWithoutEnvelope() {
        String body = """
                [{"date": "2026-07-15", "total_net_inflow": -100.0}]
                """;

        EtfFlowResponse response = SosoValueEtfFlowClient.parse(body, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() quand 'code' != 0 (erreur métier avec HTTP 200)")
    void parse_returnsInvalid_whenCodeNotZero() {
        String body = """
                {"code": 400101, "message": "Invalid API Key", "data": null}
                """;

        EtfFlowResponse response = SosoValueEtfFlowClient.parse(body, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() quand 'total_net_inflow' est absent de la première ligne")
    void parse_returnsInvalid_whenTotalNetInflowMissing() {
        String body = """
                {"code": 0, "message": "success", "data": [{"date": "2026-07-15", "total_value_traded": 100.0}]}
                """;

        EtfFlowResponse response = SosoValueEtfFlowClient.parse(body, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() quand 'date' est absent de la première ligne")
    void parse_returnsInvalid_whenDateMissing() {
        String body = """
                {"code": 0, "message": "success", "data": [{"total_net_inflow": -100.0}]}
                """;

        EtfFlowResponse response = SosoValueEtfFlowClient.parse(body, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() sur un corps JSON invalide/vide, sans exception")
    void parse_returnsInvalid_whenBodyInvalid() {
        assertFalse(SosoValueEtfFlowClient.parse(null, EtfFlowAsset.BTC).isValid());
        assertFalse(SosoValueEtfFlowClient.parse("", EtfFlowAsset.BTC).isValid());
        assertFalse(SosoValueEtfFlowClient.parse("not json", EtfFlowAsset.BTC).isValid());
        assertFalse(SosoValueEtfFlowClient.parse("{}", EtfFlowAsset.BTC).isValid());
    }

    @Test
    @DisplayName("fetch() mappe correctement une réponse 200 valide de bout en bout, avec le header d'auth")
    void fetch_mapsValid200Response() throws IOException {
        String[] seenHeader = new String[1];
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/etfs/summary-history", exchange -> {
            seenHeader[0] = exchange.getRequestHeaders().getFirst("x-soso-api-key");
            byte[] body = VALID_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SosoValueEtfFlowClient client = new SosoValueEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.SOSOVALUE, "test-api-key", "", "http://127.0.0.1:" + server.getAddress().getPort());

        EtfFlowResponse response = client.fetch(credential, EtfFlowAsset.BTC);

        assertTrue(response.isValid());
        assertEquals(LocalDate.of(2026, 7, 15), response.getDate());
        assertEquals(-55066297.0, response.getTotal(), 0.001);
        assertEquals("test-api-key", seenHeader[0]);
    }

    @Test
    @DisplayName("fetch() retombe sur invalid() en cas de 500, sans exception")
    void fetch_returnsInvalid_on5xx() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/etfs/summary-history", exchange -> {
            byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SosoValueEtfFlowClient client = new SosoValueEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.SOSOVALUE, "test-api-key", "", "http://127.0.0.1:" + server.getAddress().getPort());

        EtfFlowResponse response = client.fetch(credential, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("fetch() retombe sur invalid() quand l'hôte est injoignable (pas d'exception)")
    void fetch_returnsInvalid_whenHostUnreachable() {
        SosoValueEtfFlowClient client = new SosoValueEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.SOSOVALUE, "test-api-key", "", "http://127.0.0.1:1");

        EtfFlowResponse response = client.fetch(credential, EtfFlowAsset.ETH);

        assertFalse(response.isValid());
    }
}
