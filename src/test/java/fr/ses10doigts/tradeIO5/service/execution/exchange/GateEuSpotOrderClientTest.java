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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GateEuSpotOrderClient : corps, text t-, lecture (repli ordres terminés), annulation, erreurs (serveur HTTP local, aucun appel réel)")
class GateEuSpotOrderClientTest {

    private record Received(String method, String uri, String body, String key, String sign) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    /** Surcharge par chemin exact : {statut, corps}. */
    private final Map<String, Object[]> byPath = new ConcurrentHashMap<>();
    private GateEuSpotOrderClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders().getFirst("KEY"),
                    ex.getRequestHeaders().getFirst("SIGN")));
            Object[] special = byPath.get(ex.getRequestURI().getPath());
            int code = special == null ? status.get() : (Integer) special[0];
            byte[] bytes = (special == null ? response.get() : (String) special[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new GateEuSpotOrderClient(new FixedDomainClock(Instant.parse("2026-11-04T00:00:00Z")), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential cred(boolean providerEnabled) {
        return ApiCredential.builder().apiKey("gate-trade-key").secretKey("gate-secret")
                .webProvider(WebProvider.builder().code(WebProviderCode.GATE_EU).enabled(providerEnabled)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4").build()).build();
    }

    private ApiCredential cred() {
        return cred(true);
    }

    private static OrderRequest request() {
        return new OrderRequest("BTC-EUR", StepSide.BUY, StepOrderType.LIMIT_IOC, new BigDecimal("0.00012"),
                new BigDecimal("100000.5"), "tio1abc");
    }

    @Test
    @DisplayName("text : préfixe t-, clOrdId court conservé, long haché à 28 caractères")
    void textOf() {
        assertEquals("t-tio1abc", GateEuSpotOrderClient.textOf("tio1abc"));
        String longText = GateEuSpotOrderClient.textOf("x".repeat(60));
        assertEquals(30, longText.length());
        assertTrue(longText.startsWith("t-"));
        assertEquals(longText, GateEuSpotOrderClient.textOf("x".repeat(60)));
    }

    @Test
    @DisplayName("submit : POST /spot/orders, limit IOC, text t-, KEY/SIGN, jamais d'ordre au marché")
    void submitBody() throws Exception {
        response.set("{\"id\":\"123\",\"text\":\"t-tio1abc\",\"status\":\"open\"}");
        OrderAck ack = client.submit(cred(), request());
        assertEquals("123", ack.ordId());
        assertEquals("tio1abc", ack.clOrdId());
        Received r = received.getFirst();
        assertEquals("POST", r.method());
        assertEquals("/api/v4/spot/orders", r.uri());
        assertEquals("gate-trade-key", r.key());
        assertFalse(r.sign().isBlank());
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals("t-tio1abc", body.path("text").asText());
        assertEquals("BTC_EUR", body.path("currency_pair").asText());
        assertEquals("limit", body.path("type").asText());
        assertEquals("spot", body.path("account").asText());
        assertEquals("buy", body.path("side").asText());
        assertEquals("0.00012", body.path("amount").asText());
        assertEquals("100000.5", body.path("price").asText());
        assertEquals("ioc", body.path("time_in_force").asText());
    }

    @Test
    @DisplayName("submit : 401/403 clé refusée, 400 doublon / rejet, 500/429 issue inconnue, corps illisible inconnu")
    void submitErrors() {
        status.set(401);
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        status.set(403);
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        status.set(400);
        response.set("{\"label\":\"ORDER_EXISTS\",\"message\":\"text already used\"}");
        assertTrue(assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request())).duplicateClientOrderId());
        response.set("{\"label\":\"BALANCE_NOT_ENOUGH\",\"message\":\"no funds\"}");
        OrderRejectedException rejected = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertEquals("BALANCE_NOT_ENOUGH", rejected.getCode());
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
    @DisplayName("provider désactivé ou secret absent : aucun appel réseau")
    void disabledProvider() {
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(false), request()));
        assertThrows(TradeCredentialRejectedException.class, () -> client.query(cred(false), "BTC-EUR", "tio1abc"));
        ApiCredential noSecret = ApiCredential.builder().apiKey("k").webProvider(cred().getWebProvider()).build();
        assertThrows(TradeCredentialRejectedException.class, () -> client.cancel(noSecret, "BTC-EUR", "tio1abc"));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("query : lecture par text (rempli, partiel, vivant, inconnu) ; 500 indisponible")
    void query() {
        response.set("""
                {"id":"1","text":"t-tio1abc","status":"closed","finish_as":"filled","amount":"0.0001","left":"0","avg_deal_price":"99999.9"}""");
        OrderState filled = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals(OrderPhase.FILLED, filled.phase());
        assertEquals(0, new BigDecimal("99999.9").compareTo(filled.avgPx()));
        assertEquals("tio1abc", filled.clOrdId());
        assertEquals("/api/v4/spot/orders/t-tio1abc?currency_pair=BTC_EUR", received.getFirst().uri());

        response.set("{\"id\":\"1\",\"status\":\"open\",\"amount\":\"1\",\"left\":\"0.6\",\"avg_deal_price\":\"10\"}");
        OrderState partial = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals(OrderPhase.PARTIALLY_FILLED, partial.phase());
        assertEquals(0, new BigDecimal("0.4").compareTo(partial.accFillSz()));

        response.set("{\"id\":\"1\",\"status\":\"open\",\"amount\":\"1\",\"left\":\"1\",\"avg_deal_price\":\"0\"}");
        OrderState live = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals(OrderPhase.LIVE, live.phase());
        assertNull(live.avgPx());

        response.set("{\"id\":\"1\",\"status\":\"nouveau\",\"amount\":\"1\",\"left\":\"1\"}");
        assertEquals(OrderPhase.UNKNOWN, client.query(cred(), "BTC-EUR", "tio1abc").phase());

        status.set(500);
        assertThrows(ExchangeUnavailableException.class, () -> client.query(cred(), "BTC-EUR", "tio1abc"));
    }

    @Test
    @DisplayName("query : 404 (IOC déjà terminé) => repli sur les ordres terminés filtrés par text ; absent => introuvable")
    void queryFinishedFallback() {
        byPath.put("/api/v4/spot/orders/t-tio1abc", new Object[]{404, "{\"label\":\"ORDER_NOT_FOUND\"}"});
        byPath.put("/api/v4/spot/orders", new Object[]{200, """
                [{"id":"9","text":"t-autre","status":"closed","amount":"1","left":"0"},
                 {"id":"10","text":"t-tio1abc","status":"cancelled","finish_as":"ioc","amount":"1","left":"0.7","avg_deal_price":"50"}]"""});
        OrderState state = client.query(cred(), "BTC-EUR", "tio1abc");
        assertEquals("10", state.ordId());
        assertEquals(OrderPhase.CANCELED, state.phase());
        assertTrue(state.terminal());
        assertEquals(0, new BigDecimal("0.3").compareTo(state.accFillSz()));
        String listUri = received.get(1).uri();
        assertTrue(listUri.startsWith("/api/v4/spot/orders?currency_pair=BTC_EUR&status=finished&from="));

        byPath.put("/api/v4/spot/orders", new Object[]{200, "[]"});
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-EUR", "tio1abc"));
    }

    @Test
    @DisplayName("cancel : DELETE par text avec currency_pair ; 404 introuvable")
    void cancel() {
        response.set("{\"status\":\"cancelled\"}");
        client.cancel(cred(), "BTC-EUR", "tio1abc");
        Received r = received.getFirst();
        assertEquals("DELETE", r.method());
        assertEquals("/api/v4/spot/orders/t-tio1abc?currency_pair=BTC_EUR", r.uri());
        status.set(404);
        assertThrows(OrderNotFoundException.class, () -> client.cancel(cred(), "BTC-EUR", "tio1abc"));
    }

    @Test
    @DisplayName("fills : GET /spot/my_trades?order_id, frais en coût positif, instId BASE-QUOTE")
    void fills() {
        response.set("""
                [{"id":"t1","order_id":"77","side":"buy","price":"100","amount":"0.1","fee":"-0.0003","fee_currency":"btc","create_time_ms":"1793000000000.5"}]""");
        List<Fill> fills = client.fills(cred(), "BTC-EUR", "77");
        assertEquals(1, fills.size());
        Fill fill = fills.getFirst();
        assertEquals("BTC-EUR", fill.instId());
        assertEquals(StepSide.BUY, fill.side());
        assertEquals(0, new BigDecimal("0.0003").compareTo(fill.fee()));
        assertEquals("BTC", fill.feeCcy());
        assertEquals("/api/v4/spot/my_trades?currency_pair=BTC_EUR&order_id=77&limit=100", received.getFirst().uri());
        assertThrows(ExchangeUnavailableException.class, () -> client.fills(cred(), "BTC-EUR", "../x"));
    }

    @Test
    @DisplayName("validateur local : accepte sans appel réseau")
    void localValidator() {
        ValidationReport report = new GateEuLocalOrderValidator().validate(cred(), request());
        assertTrue(report.accepted());
        assertTrue(received.isEmpty());
        assertEquals(WebProviderCode.GATE_EU, new GateEuLocalOrderValidator().provider());
    }
}
