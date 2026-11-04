package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.BitvavoRequests;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BitvavoSpotOrderClient : corps, operatorId, clientOrderId UUID, signature, lecture, annulation, erreurs (serveur HTTP local, aucun appel réel)")
class BitvavoSpotOrderClientTest {

    private record Received(String method, String uri, String body, String key, String signature, String timestamp) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET = "bv-trade-secret";

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private BitvavoSpotOrderClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst("Bitvavo-Access-Key"), ex.getRequestHeaders().getFirst("Bitvavo-Access-Signature"),
                    ex.getRequestHeaders().getFirst("Bitvavo-Access-Timestamp")));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new BitvavoSpotOrderClient(new FixedDomainClock(Instant.parse("2026-11-04T00:00:00Z")), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential cred(boolean providerEnabled) {
        return ApiCredential.builder().apiKey("bv-trade-key").secretKey(SECRET)
                .webProvider(WebProvider.builder().code(WebProviderCode.BITVAVO).enabled(providerEnabled)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v2").build()).build();
    }

    private ApiCredential cred() {
        return cred(true);
    }

    private static OrderRequest request() {
        return new OrderRequest("BTC-EUR", StepSide.BUY, StepOrderType.LIMIT_IOC, new BigDecimal("0.00012"),
                new BigDecimal("100000.5"), "tio1abc");
    }

    private static String bitvavoError(int code) {
        return "{\"errorCode\":" + code + ",\"error\":\"msg " + code + "\"}";
    }

    @Test
    @DisplayName("submit : POST /v2/order, limit IOC, operatorId, clientOrderId UUID déterministe, signature valide, jamais d'ordre au marché")
    void submitBody() throws Exception {
        response.set("{\"orderId\":\"ord-1\",\"clientOrderId\":\"x\",\"status\":\"new\"}");
        OrderAck ack = client.submit(cred(), request());
        assertEquals("ord-1", ack.ordId());
        assertEquals("tio1abc", ack.clOrdId());
        Received r = received.getFirst();
        assertEquals("POST", r.method());
        assertEquals("/v2/order", r.uri());
        assertEquals("bv-trade-key", r.key());
        assertEquals(BitvavoRequests.sign(SECRET, r.timestamp() + "POST/v2/order" + r.body()), r.signature());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals("BTC-EUR", body.path("market").asText());
        assertEquals("limit", body.path("orderType").asText());
        assertEquals("buy", body.path("side").asText());
        assertEquals("0.00012", body.path("amount").asText());
        assertEquals("100000.5", body.path("price").asText());
        assertEquals("IOC", body.path("timeInForce").asText());
        assertEquals(BitvavoSpotOrderClient.OPERATOR_ID, body.path("operatorId").asLong());
        assertTrue(body.path("operatorId").isIntegralNumber());
        assertEquals(BitvavoSpotOrderClient.clientOrderId("tio1abc"), body.path("clientOrderId").asText());
        assertEquals(UUID.fromString(body.path("clientOrderId").asText()).toString(), body.path("clientOrderId").asText());
        assertNotEquals(BitvavoSpotOrderClient.clientOrderId("tio1abc"), BitvavoSpotOrderClient.clientOrderId("tio1abd"));
    }

    @Test
    @DisplayName("submit : 403 clé refusée, 220 doublon, 216 rejet, 409 marché en pause, 429/503/500 issue inconnue, corps illisible inconnu")
    void submitErrors() {
        status.set(403);
        response.set(bitvavoError(309));
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        status.set(400);
        response.set(bitvavoError(220));
        OrderRejectedException duplicate = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertTrue(duplicate.duplicateClientOrderId());
        response.set(bitvavoError(216));
        OrderRejectedException rejected = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertEquals("216", rejected.getCode());
        assertFalse(rejected.duplicateClientOrderId());
        status.set(409);
        response.set(bitvavoError(431));
        assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        for (int code : new int[]{429, 503, 500}) {
            status.set(code);
            assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
        }
        status.set(200);
        response.set("pas du json");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("provider désactivé, clé ou secret absent : aucun appel réseau")
    void disabledProvider() {
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(false), request()));
        assertThrows(TradeCredentialRejectedException.class, () -> client.query(cred(false), "BTC-EUR", "tio1abc"));
        ApiCredential noSecret = ApiCredential.builder().apiKey("k").webProvider(cred().getWebProvider()).build();
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(noSecret, request()));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("query : GET par clientOrderId, rempli, partiel annulé, vivant, statut inconnu, 240 / 404 introuvable")
    void query() {
        response.set("""
                {"orderId":"ord-1","amount":"0.0001","filledAmount":"0.0001","filledAmountQuote":"9.99999","status":"filled"}""");
        OrderState filled = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals(OrderPhase.FILLED, filled.phase());
        assertEquals(0, new BigDecimal("99999.9").compareTo(filled.avgPx()));
        assertEquals("tio1abc", filled.clOrdId());
        assertEquals("/v2/order?market=BTC-EUR&clientOrderId=" + BitvavoSpotOrderClient.clientOrderId("tio1abc"), received.getFirst().uri());

        response.set("""
                {"orderId":"ord-1","amount":"1","filledAmount":"0.4","filledAmountQuote":"4","status":"canceled"}""");
        OrderState partial = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals(OrderPhase.CANCELED, partial.phase());
        assertTrue(partial.terminal());
        assertEquals(0, new BigDecimal("0.4").compareTo(partial.accFillSz()));

        response.set("{\"orderId\":\"o\",\"amount\":\"1\",\"filledAmount\":\"0\",\"status\":\"new\"}");
        OrderState live = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals(OrderPhase.LIVE, live.phase());
        assertNull(live.avgPx());

        response.set("{\"orderId\":\"o\",\"amount\":\"1\",\"filledAmount\":\"0.2\",\"status\":\"partiallyFilled\"}");
        assertEquals(OrderPhase.PARTIALLY_FILLED, client.query(cred(), "BTC-EUR", "tio1abc").phase());

        response.set("{\"orderId\":\"o\",\"amount\":\"1\",\"filledAmount\":\"0\",\"status\":\"nouveau\"}");
        assertEquals(OrderPhase.UNKNOWN, client.query(cred(), "BTC-EUR", "tio1abc").phase());

        status.set(404);
        response.set(bitvavoError(240));
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-EUR", "tio1abc"));
        status.set(400);
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-EUR", "tio1abc"));
        status.set(500);
        response.set("{}");
        assertThrows(ExchangeUnavailableException.class, () -> client.query(cred(), "BTC-EUR", "tio1abc"));
        assertThrows(ExchangeUnavailableException.class, () -> client.query(cred(), "../x", "tio1abc"));
    }

    @Test
    @DisplayName("cancel : DELETE par clientOrderId avec market et operatorId ; 240 introuvable")
    void cancel() {
        response.set("{\"orderId\":\"o\"}");
        client.cancel(cred(), "BTC-EUR", "tio1abc");
        Received r = received.getFirst();
        assertEquals("DELETE", r.method());
        String expectedUri = "/v2/order?market=BTC-EUR&clientOrderId=" + BitvavoSpotOrderClient.clientOrderId("tio1abc") + "&operatorId=5";
        assertEquals(expectedUri, r.uri());
        assertEquals(BitvavoRequests.sign(SECRET, r.timestamp() + "DELETE" + expectedUri), r.signature());
        status.set(404);
        response.set(bitvavoError(240));
        assertThrows(OrderNotFoundException.class, () -> client.cancel(cred(), "BTC-EUR", "tio1abc"));
    }

    @Test
    @DisplayName("fills : tableau fills de GET /order?orderId=, instId BASE-QUOTE, sens de l'ordre, frais de la ligne")
    void fills() {
        response.set("""
                {"orderId":"ord-1","side":"buy","feeCurrency":"BTC","fills":[{"id":"f1","timestamp":1780000000000,"amount":"0.1",
                 "price":"100","taker":true,"fee":"0.0003","feeCurrency":"BTC","settled":true}]}""");
        List<Fill> fills = client.fills(cred(), "BTC-EUR", "ord-1");
        assertEquals(1, fills.size());
        Fill fill = fills.getFirst();
        assertEquals("f1", fill.tradeId());
        assertEquals("BTC-EUR", fill.instId());
        assertEquals(StepSide.BUY, fill.side());
        assertEquals(0, new BigDecimal("0.0003").compareTo(fill.fee()));
        assertEquals("BTC", fill.feeCcy());
        assertEquals(Instant.ofEpochMilli(1780000000000L), fill.ts());
        assertEquals("/v2/order?market=BTC-EUR&orderId=ord-1", received.getFirst().uri());
        assertThrows(ExchangeUnavailableException.class, () -> client.fills(cred(), "BTC-EUR", "../x"));
    }

    @Test
    @DisplayName("validateur local : accepte sans appel réseau")
    void localValidator() {
        ValidationReport report = new BitvavoLocalOrderValidator().validate(cred(), request());
        assertTrue(report.accepted());
        assertTrue(received.isEmpty());
        assertEquals(WebProviderCode.BITVAVO, new BitvavoLocalOrderValidator().provider());
    }
}
