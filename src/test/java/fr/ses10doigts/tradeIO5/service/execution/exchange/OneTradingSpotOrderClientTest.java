package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OneTradingSpotOrderClient : corps, client_id UUID, lecture, annulation, erreurs (serveur HTTP local, aucun appel réel)")
class OneTradingSpotOrderClientTest {

    private record Received(String method, String uri, String body, String auth) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private OneTradingSpotOrderClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new OneTradingSpotOrderClient(new FixedDomainClock(Instant.parse("2026-11-04T00:00:00Z")), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential cred(boolean providerEnabled) {
        return ApiCredential.builder().apiKey("ot-trade-key")
                .webProvider(WebProvider.builder().code(WebProviderCode.ONE_TRADING).enabled(providerEnabled)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/fast/v1").build()).build();
    }

    private ApiCredential cred() {
        return cred(true);
    }

    private static OrderRequest request() {
        return new OrderRequest("BTC-USDC", StepSide.BUY, StepOrderType.LIMIT_IOC, new BigDecimal("0.00012"),
                new BigDecimal("100000.5"), "tio1abc");
    }

    @Test
    @DisplayName("submit : POST /account/orders, LIMIT IOC, client_id UUID déterministe, Bearer, jamais d'ordre au marché")
    void submitBody() throws Exception {
        response.set("{\"order_id\":\"ord-1\",\"client_id\":\"x\",\"status\":\"OPEN\"}");
        OrderAck ack = client.submit(cred(), request());
        assertEquals("ord-1", ack.ordId());
        assertEquals("tio1abc", ack.clOrdId());
        Received r = received.getFirst();
        assertEquals("POST", r.method());
        assertEquals("/fast/v1/account/orders", r.uri());
        assertEquals("Bearer ot-trade-key", r.auth());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals("BTC_USDC", body.path("instrument_code").asText());
        assertEquals("LIMIT", body.path("type").asText());
        assertEquals("BUY", body.path("side").asText());
        assertEquals("0.00012", body.path("amount").asText());
        assertEquals("100000.5", body.path("price").asText());
        assertEquals("IMMEDIATE_OR_CANCELLED", body.path("time_in_force").asText());
        assertEquals(OneTradingSpotOrderClient.clientId("tio1abc"), body.path("client_id").asText());
        assertEquals(java.util.UUID.fromString(body.path("client_id").asText()).toString(), body.path("client_id").asText());
        assertNotEquals(OneTradingSpotOrderClient.clientId("tio1abc"), OneTradingSpotOrderClient.clientId("tio1abd"));
    }

    @Test
    @DisplayName("submit : 401/403 clé refusée, 409 doublon, 422 rejet, 500/429 issue inconnue, corps illisible inconnu")
    void submitErrors() {
        status.set(401);
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        status.set(403);
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        status.set(409);
        OrderRejectedException duplicate = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertTrue(duplicate.duplicateClientOrderId());
        status.set(422);
        response.set("{\"error\":\"INSUFFICIENT_FUNDS\"}");
        OrderRejectedException rejected = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertEquals("INSUFFICIENT_FUNDS", rejected.getCode());
        assertFalse(rejected.duplicateClientOrderId());
        status.set(500);
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
        status.set(429);
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
        status.set(200);
        response.set("pas du json");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("provider désactivé ou clé absente : aucun appel réseau")
    void disabledProvider() {
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(false), request()));
        assertThrows(TradeCredentialRejectedException.class, () -> client.query(cred(false), "BTC-USDC", "tio1abc"));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("query : GET par client_id, rempli, partiel annulé, vivant, 404 introuvable")
    void query() {
        response.set("""
                {"order":{"order_id":"ord-1","client_id":"c","amount":"0.0001","filled_amount":"0.0001","average_price":"99999.9","status":"FILLED_FULLY"},"trades":[]}""");
        OrderState filled = client.query(cred(), "BTC-USDC", "tio1abc");
        assertEquals(OrderPhase.FILLED, filled.phase());
        assertEquals(0, new BigDecimal("99999.9").compareTo(filled.avgPx()));
        assertEquals("tio1abc", filled.clOrdId());
        assertEquals("/fast/v1/account/orders/client/" + OneTradingSpotOrderClient.clientId("tio1abc"), received.getFirst().uri());

        response.set("""
                {"order":{"order_id":"ord-1","amount":"1","filled_amount":"0.4","average_price":"10","status":"FILLED_CLOSED"}}""");
        OrderState partial = client.query(cred(), "BTC-USDC", "tio1abc");
        assertEquals(OrderPhase.CANCELED, partial.phase());
        assertTrue(partial.terminal());
        assertEquals(0, new BigDecimal("0.4").compareTo(partial.accFillSz()));

        response.set("{\"order\":{\"order_id\":\"o\",\"amount\":\"1\",\"filled_amount\":\"0\",\"average_price\":\"0\",\"status\":\"OPEN\"}}");
        OrderState live = client.query(cred(), "BTC-USDC", "tio1abc");
        assertEquals(OrderPhase.LIVE, live.phase());
        assertNull(live.avgPx());

        response.set("{\"order\":{\"order_id\":\"o\",\"amount\":\"1\",\"filled_amount\":\"0\",\"status\":\"NOUVEAU_STATUT\"}}");
        assertEquals(OrderPhase.UNKNOWN, client.query(cred(), "BTC-USDC", "tio1abc").phase());

        status.set(404);
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-USDC", "tio1abc"));
        status.set(500);
        assertThrows(ExchangeUnavailableException.class, () -> client.query(cred(), "BTC-USDC", "tio1abc"));
    }

    @Test
    @DisplayName("cancel : DELETE par client_id avec instrument_code ; 404 introuvable")
    void cancel() {
        response.set("{\"status\":\"CANCELLED\"}");
        client.cancel(cred(), "BTC-USDC", "tio1abc");
        Received r = received.getFirst();
        assertEquals("DELETE", r.method());
        assertEquals("/fast/v1/account/orders/client/" + OneTradingSpotOrderClient.clientId("tio1abc") + "?instrument_code=BTC_USDC", r.uri());
        status.set(404);
        assertThrows(OrderNotFoundException.class, () -> client.cancel(cred(), "BTC-USDC", "tio1abc"));
    }

    @Test
    @DisplayName("fills : GET /account/orders/{id}/trades, frais en coût positif, instId BASE-QUOTE")
    void fills() {
        response.set("""
                {"trade_history":[{"fee":{"fee_amount":"-0.0003","fee_currency":"BTC"},"trade":{"trade_id":"t1","order_id":"ord-1",
                 "instrument_code":"BTC_USDC","side":"BUY","price":"100","amount":"0.1","time":"2026-11-04T10:00:00Z"}}]}""");
        List<Fill> fills = client.fills(cred(), "BTC-USDC", "ord-1");
        assertEquals(1, fills.size());
        Fill fill = fills.getFirst();
        assertEquals("BTC-USDC", fill.instId());
        assertEquals(StepSide.BUY, fill.side());
        assertEquals(0, new BigDecimal("0.0003").compareTo(fill.fee()));
        assertEquals("BTC", fill.feeCcy());
        assertEquals("/fast/v1/account/orders/ord-1/trades", received.getFirst().uri());
        assertThrows(ExchangeUnavailableException.class, () -> client.fills(cred(), "BTC-USDC", "../x"));
    }

    @Test
    @DisplayName("validateur local : accepte sans appel réseau")
    void localValidator() {
        ValidationReport report = new OneTradingLocalOrderValidator().validate(cred(), request());
        assertTrue(report.accepted());
        assertTrue(received.isEmpty());
        assertEquals(WebProviderCode.ONE_TRADING, new OneTradingLocalOrderValidator().provider());
    }
}
