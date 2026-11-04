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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RevolutXSpotOrderClient : corps IOC signé, client_order_id UUID, recherche par client_order_id, annulation, erreurs (serveur HTTP local, aucun appel réel)")
class RevolutXSpotOrderClientTest {

    private record Received(String method, String path, String query, String body, String timestamp, String signature) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private KeyPair keys;
    private final List<Received> received = new ArrayList<>();
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private RevolutXSpotOrderClient client;

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders().getFirst("X-Revx-Timestamp"),
                    ex.getRequestHeaders().getFirst("X-Revx-Signature")));
            String body = routes.getOrDefault(ex.getRequestURI().getPath(), "{\"data\":[]}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new RevolutXSpotOrderClient(new FixedDomainClock(Instant.parse("2026-11-04T00:00:00Z")), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential cred(boolean providerEnabled) {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        return ApiCredential.builder().apiKey("rx-trade-key").secretKey(pem)
                .webProvider(WebProvider.builder().code(WebProviderCode.REVOLUT_X).enabled(providerEnabled)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/1.0").build()).build();
    }

    private ApiCredential cred() {
        return cred(true);
    }

    private static OrderRequest request() {
        return new OrderRequest("BTC-USD", StepSide.BUY, StepOrderType.LIMIT_IOC, new BigDecimal("0.00012"),
                new BigDecimal("100000.5"), "tio1abc");
    }

    private boolean signatureValid(Received r) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keys.getPublic());
        verifier.update((r.timestamp() + r.method() + r.path() + (r.query() == null ? "" : r.query()) + r.body()).getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(r.signature()));
    }

    @Test
    @DisplayName("submit : POST /api/1.0/orders, limit IOC allow_taker, client_order_id UUID déterministe, signature (corps compris) valide")
    void submitBody() throws Exception {
        routes.put("/api/1.0/orders", "{\"data\":{\"venue_order_id\":\"ord-1\",\"client_order_id\":\"x\",\"state\":\"new\"}}");
        OrderAck ack = client.submit(cred(), request());
        assertEquals("ord-1", ack.ordId());
        assertEquals("tio1abc", ack.clOrdId());
        Received r = received.getFirst();
        assertEquals("POST", r.method());
        assertEquals("/api/1.0/orders", r.path());
        assertTrue(signatureValid(r));
        JsonNode body = MAPPER.readTree(r.body());
        assertEquals(RevolutXSpotOrderClient.clientId("tio1abc"), body.path("client_order_id").asText());
        assertEquals("BTC-USD", body.path("symbol").asText());
        assertEquals("buy", body.path("side").asText());
        JsonNode limit = body.path("order_configuration").path("limit");
        assertEquals("0.00012", limit.path("base_size").asText());
        assertEquals("100000.5", limit.path("price").asText());
        assertEquals("ioc", limit.path("time_in_force").asText());
        assertEquals("allow_taker", limit.path("execution_instructions").get(0).asText());
        assertTrue(body.path("order_configuration").path("market").isMissingNode());
        assertEquals(RevolutXSpotOrderClient.clientId("tio1abc"), RevolutXSpotOrderClient.clientId("tio1abc"));
    }

    @Test
    @DisplayName("submit : 400 = rejet certain, 401/403 = clé refusée, 409/429/5xx = issue inconnue, état rejected = rejet")
    void submitErrors() {
        status.set(400);
        routes.put("/api/1.0/orders", "{\"error_id\":\"e\",\"message\":\"No such pair: BTC-BTC\",\"timestamp\":1}");
        OrderRejectedException rejected = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertTrue(rejected.getMessage().contains("No such pair"));
        status.set(401);
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        for (int code : new int[]{409, 429, 500}) {
            status.set(code);
            assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
        }
        status.set(200);
        routes.put("/api/1.0/orders", "{\"data\":{\"venue_order_id\":\"o\",\"state\":\"rejected\"}}");
        assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        routes.put("/api/1.0/orders", "{}");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("provider désactivé : aucun appel réseau")
    void disabledProvider() {
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(false), request()));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("query : cherche d'abord les ordres actifs, puis l'historique, par client_order_id dérivé ; introuvable = OrderNotFound")
    void queryByClientOrderId() {
        String cid = RevolutXSpotOrderClient.clientId("tio1abc");
        routes.put("/api/1.0/orders/historical", "{\"data\":[{\"id\":\"v-9\",\"client_order_id\":\"" + cid
                + "\",\"filled_quantity\":\"0.00012\",\"quantity\":\"0.00012\",\"average_fill_price\":\"99999\",\"status\":\"filled\"}],\"metadata\":{}}");
        OrderState state = client.query(cred(), "BTC-USD", "tio1abc");
        assertEquals(OrderPhase.FILLED, state.phase());
        assertEquals("v-9", state.ordId());
        assertEquals(0, new BigDecimal("0.00012").compareTo(state.accFillSz()));
        assertEquals(0, new BigDecimal("99999").compareTo(state.avgPx()));
        assertEquals("/api/1.0/orders/active", received.getFirst().path());
        assertTrue(received.get(1).query().startsWith("symbols=BTC-USD&start_date="));
        routes.put("/api/1.0/orders/historical", "{\"data\":[]}");
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-USD", "autre"));
    }

    @Test
    @DisplayName("cancel : retrouve l'ordre actif puis DELETE /orders/{id} ; aucun actif = OrderNotFound")
    void cancel() {
        String cid = RevolutXSpotOrderClient.clientId("tio1abc");
        routes.put("/api/1.0/orders/active", "{\"data\":[{\"id\":\"v-3\",\"client_order_id\":\"" + cid + "\",\"status\":\"new\"}],\"metadata\":{}}");
        client.cancel(cred(), "BTC-USD", "tio1abc");
        Received last = received.getLast();
        assertEquals("DELETE", last.method());
        assertEquals("/api/1.0/orders/v-3", last.path());
        routes.put("/api/1.0/orders/active", "{\"data\":[]}");
        status.set(200);
        assertThrows(OrderNotFoundException.class, () -> client.cancel(cred(), "BTC-USD", "inconnu"));
    }

    @Test
    @DisplayName("fills : frais total de l'ordre sur le premier remplissage, tid conservé, identifiant invalide refusé")
    void fills() {
        routes.put("/api/1.0/orders/v-1", "{\"data\":{\"client_order_id\":\"c\",\"side\":\"buy\",\"total_fee\":\"0.10\",\"fee_currency\":\"USD\"}}");
        routes.put("/api/1.0/orders/fills/v-1", """
                {"data":[{"tdt":1762000000000,"aid":"BTC","pc":"USD","p":"90000","q":"0.001","tid":"t-1","oid":"v-1","s":"buy","im":false},
                         {"tdt":1762000001000,"aid":"BTC","pc":"USD","p":"90010","q":"0.002","tid":"t-2","oid":"v-1","im":false}]}""");
        List<Fill> fills = client.fills(cred(), "BTC-USD", "v-1");
        assertEquals(2, fills.size());
        assertEquals("t-1", fills.getFirst().tradeId());
        assertEquals(StepSide.BUY, fills.get(1).side());
        assertEquals(0, new BigDecimal("0.10").compareTo(fills.getFirst().fee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(fills.get(1).fee()));
        assertEquals("USD", fills.getFirst().feeCcy());
        assertThrows(ExchangeUnavailableException.class, () -> client.fills(cred(), "BTC-USD", "../x"));
    }

    @Test
    @DisplayName("phaseOf : statuts documentés, quantité complète = rempli, inconnu = UNKNOWN")
    void phases() {
        assertEquals(OrderPhase.FILLED, RevolutXSpotOrderClient.phaseOf("filled", BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(OrderPhase.FILLED, RevolutXSpotOrderClient.phaseOf("cancelled", BigDecimal.ONE, BigDecimal.ONE));
        assertEquals(OrderPhase.LIVE, RevolutXSpotOrderClient.phaseOf("new", BigDecimal.ZERO, BigDecimal.ONE));
        assertEquals(OrderPhase.PARTIALLY_FILLED, RevolutXSpotOrderClient.phaseOf("partially_filled", BigDecimal.TEN, BigDecimal.valueOf(20)));
        assertEquals(OrderPhase.CANCELED, RevolutXSpotOrderClient.phaseOf("cancelled", BigDecimal.ZERO, BigDecimal.ONE));
        assertEquals(OrderPhase.CANCELED, RevolutXSpotOrderClient.phaseOf("rejected", BigDecimal.ZERO, BigDecimal.ONE));
        assertEquals(OrderPhase.UNKNOWN, RevolutXSpotOrderClient.phaseOf("replaced", BigDecimal.ZERO, BigDecimal.ONE));
    }
}
