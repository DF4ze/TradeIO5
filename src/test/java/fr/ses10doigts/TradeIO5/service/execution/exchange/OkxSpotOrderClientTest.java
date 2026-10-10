package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.OkxSignedRequests;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OkxSpotOrderClient : signature, corps exact, parsing, erreurs (serveur HTTP local)")
class OkxSpotOrderClientTest {

    private record Received(String method, String uri, String body, String key, String sign, String ts, String pass) {
    }

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private OkxSpotOrderClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var h = ex.getRequestHeaders();
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(), body, h.getFirst("OK-ACCESS-KEY"),
                    h.getFirst("OK-ACCESS-SIGN"), h.getFirst("OK-ACCESS-TIMESTAMP"), h.getFirst("OK-ACCESS-PASSPHRASE")));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        client = new OkxSpotOrderClient(new FixedDomainClock(Instant.parse("2026-10-10T00:00:00.123Z")), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential cred() {
        return ApiCredential.builder().apiKey("trade-key").secretKey("trade-secret").passphrase("trade-pass")
                .webProvider(WebProvider.builder().code(WebProviderCode.OKX)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build()).build();
    }

    private static OrderRequest request() {
        return new OrderRequest("BTC-USDC", StepSide.BUY, StepOrderType.LIMIT_IOC, new BigDecimal("0.00123"),
                new BigDecimal("100000.5"), "tio1abc");
    }

    @Test
    @DisplayName("Corps exact et signature HMAC (timestamp+POST+chemin+corps) d'un ordre limit IOC")
    void bodyAndSignature() {
        response.set("{\"code\":\"0\",\"msg\":\"\",\"data\":[{\"clOrdId\":\"tio1abc\",\"ordId\":\"777\",\"sCode\":\"0\",\"sMsg\":\"\"}]}");
        OrderAck ack = client.submit(cred(), request());
        assertEquals("777", ack.ordId());
        Received r = received.get(0);
        String expectedBody = "{\"instId\":\"BTC-USDC\",\"tdMode\":\"cash\",\"side\":\"buy\",\"ordType\":\"ioc\","
                + "\"sz\":\"0.00123\",\"px\":\"100000.5\",\"tgtCcy\":\"base_ccy\",\"clOrdId\":\"tio1abc\"}";
        assertEquals("POST", r.method());
        assertEquals("/api/v5/trade/order", r.uri());
        assertEquals(expectedBody, r.body());
        assertEquals(OkxSignedRequests.sign(r.ts(), "POST", "/api/v5/trade/order", expectedBody, "trade-secret"), r.sign());
        assertEquals("trade-key", r.key());
        assertEquals("trade-pass", r.pass());
    }

    @Test
    @DisplayName("Rejet métier (sCode 51008) => OrderRejectedException avec code ; doublon 51016 reconnu")
    void rejection() {
        response.set("{\"code\":\"1\",\"msg\":\"All operations failed\",\"data\":[{\"sCode\":\"51008\",\"sMsg\":\"insufficient\"}]}");
        OrderRejectedException e = assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request()));
        assertEquals("51008", e.getCode());
        assertFalse(e.duplicateClientOrderId());
        response.set("{\"code\":\"1\",\"msg\":\"\",\"data\":[{\"sCode\":\"51016\",\"sMsg\":\"dup\"}]}");
        assertTrue(assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request())).duplicateClientOrderId());
    }

    @Test
    @DisplayName("Clé refusée (501xx) => TradeCredentialRejectedException")
    void credentialRejected() {
        response.set("{\"code\":\"50111\",\"msg\":\"Invalid OK-ACCESS-KEY\",\"data\":[]}");
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("Réponse illisible, HTTP 500 ou erreur système => ExchangeUnavailableException (issue inconnue)")
    void unavailable() {
        response.set("not json");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
        status.set(500);
        response.set("oops");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
        status.set(200);
        response.set("{\"code\":\"50013\",\"msg\":\"System busy\",\"data\":[]}");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("query : parsing de l'état ; 51603 ou data vide => OrderNotFoundException")
    void query() {
        response.set("{\"code\":\"0\",\"data\":[{\"clOrdId\":\"tio1abc\",\"ordId\":\"777\",\"state\":\"partially_filled\",\"accFillSz\":\"0.001\",\"avgPx\":\"100000\"}]}");
        OrderState s = client.query(cred(), "BTC-USDC", "tio1abc");
        assertEquals(OrderPhase.PARTIALLY_FILLED, s.phase());
        assertEquals(0, new BigDecimal("0.001").compareTo(s.accFillSz()));
        assertEquals("GET", received.get(0).method());
        assertTrue(received.get(0).uri().startsWith("/api/v5/trade/order?instId=BTC-USDC&clOrdId=tio1abc"));
        response.set("{\"code\":\"51603\",\"msg\":\"Order does not exist\",\"data\":[]}");
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-USDC", "tio1abc"));
        response.set("{\"code\":\"0\",\"data\":[]}");
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-USDC", "tio1abc"));
    }

    @Test
    @DisplayName("fills : frais négatifs OKX => coût positif, achat/vente, devise des frais")
    void fills() {
        response.set("{\"code\":\"0\",\"data\":[{\"tradeId\":\"t1\",\"ordId\":\"777\",\"clOrdId\":\"tio1abc\",\"instId\":\"BTC-USDC\","
                + "\"side\":\"buy\",\"fillPx\":\"100000\",\"fillSz\":\"0.001\",\"fee\":\"-0.000001\",\"feeCcy\":\"BTC\",\"ts\":\"1760000000000\"}]}");
        List<Fill> fills = client.fills(cred(), "BTC-USDC", "777");
        assertEquals(1, fills.size());
        Fill f = fills.get(0);
        assertEquals(StepSide.BUY, f.side());
        assertEquals(0, new BigDecimal("0.000001").compareTo(f.fee()));
        assertEquals("BTC", f.feeCcy());
        assertEquals(Instant.ofEpochMilli(1760000000000L), f.ts());
    }

    @Test
    @DisplayName("Aucun secret dans les messages d'exception")
    void noSecretInErrors() {
        response.set("{\"code\":\"50111\",\"msg\":\"Invalid\",\"data\":[]}");
        TradeCredentialRejectedException e = assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        String text = String.valueOf(e.getMessage());
        assertFalse(text.contains("trade-secret") || text.contains("trade-key") || text.contains("trade-pass"));
    }
}
