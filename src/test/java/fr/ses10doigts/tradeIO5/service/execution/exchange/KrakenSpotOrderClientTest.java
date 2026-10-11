package fr.ses10doigts.tradeIO5.service.execution.exchange;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.KrakenSignedRequests;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KrakenSpotOrderClient : corps, signature, validate, parsing, erreurs (serveur HTTP local, aucun appel réel)")
class KrakenSpotOrderClientTest {

    private record Received(String method, String uri, String body, String key, String sign) {
    }

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final java.util.Map<String, String> routes = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private KrakenSpotOrderClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var h = ex.getRequestHeaders();
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(), body, h.getFirst("API-Key"),
                    h.getFirst("API-Sign")));
            String uri = ex.getRequestURI().toString();
            byte[] bytes = routes.getOrDefault(uri.substring(uri.lastIndexOf('/') + 1), response.get()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        InstrumentCatalog catalog = mock(InstrumentCatalog.class);
        when(catalog.nativePair(WebProviderCode.KRAKEN, "BTC-USDC")).thenReturn(Optional.of("XBTUSDC"));
        when(catalog.nativePair(WebProviderCode.KRAKEN, "ETH-USDC")).thenReturn(Optional.empty());
        client = new KrakenSpotOrderClient(new FixedDomainClock(Instant.parse("2026-10-10T00:00:00.123Z")), catalog, Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential cred() {
        String secret = Base64.getEncoder().encodeToString("kraken-secret".getBytes(StandardCharsets.UTF_8));
        return ApiCredential.builder().apiKey("kraken-key").secretKey(secret)
                .webProvider(WebProvider.builder().code(WebProviderCode.KRAKEN)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build()).build();
    }

    private static OrderRequest request() {
        return new OrderRequest("BTC-USDC", StepSide.BUY, StepOrderType.LIMIT_IOC, new BigDecimal("0.00012"),
                new BigDecimal("100000.5"), "tio1abc");
    }

    @Test
    @DisplayName("submit : paire native, limit IOC, oflags fciq, cl_ord_id haché, en-têtes de signature")
    void submitBody() {
        response.set("{\"error\":[],\"result\":{\"txid\":[\"OABC-123\"],\"descr\":{\"order\":\"buy ...\"}}}");
        OrderAck ack = client.submit(cred(), request());
        assertEquals("OABC-123", ack.ordId());
        Received r = received.get(0);
        assertEquals("POST", r.method());
        assertEquals("/0/private/AddOrder", r.uri());
        assertEquals("kraken-key", r.key());
        assertNotNull(r.sign());
        assertTrue(r.body().contains("pair=XBTUSDC"));
        assertTrue(r.body().contains("type=buy"));
        assertTrue(r.body().contains("ordertype=limit"));
        assertTrue(r.body().contains("timeinforce=IOC"));
        assertTrue(r.body().contains("oflags=fciq"));
        assertTrue(r.body().contains("volume=0.00012"));
        assertTrue(r.body().contains("price=100000.5"));
        assertTrue(r.body().contains("cl_ord_id=" + KrakenSpotOrderClient.krakenClientId("tio1abc")));
        assertTrue(r.body().contains("nonce="));
        assertFalse(r.body().contains("validate"));
        assertEquals(32, KrakenSpotOrderClient.krakenClientId("tio1abc").length());
    }

    @Test
    @DisplayName("validate : validate=true, accepté sans txid ; refus EOrder:* => rapport refusé ; réponse filtrée")
    void validate() {
        response.set("{\"error\":[],\"result\":{\"descr\":{\"order\":\"buy 0.00012 XBTUSDC @ limit 100000.5\"},\"secret\":\"x\"}}");
        ValidationReport ok = client.validate(cred(), request());
        assertTrue(ok.accepted());
        assertTrue(received.get(0).body().contains("validate=true"));
        assertFalse(ok.rawFiltered().contains("secret"));
        assertTrue(ok.rawFiltered().contains("buy 0.00012"));
        response.set("{\"error\":[\"EOrder:Order minimum not met\"],\"result\":{}}");
        ValidationReport ko = client.validate(cred(), request());
        assertFalse(ko.accepted());
        assertEquals("EOrder:Order minimum not met", ko.exchangeCode());
    }

    @Test
    @DisplayName("validate : paire absente du catalogue => refus local, aucun appel")
    void validateUnknownPair() {
        OrderRequest eth = new OrderRequest("ETH-USDC", StepSide.BUY, StepOrderType.LIMIT_IOC, BigDecimal.ONE, BigDecimal.TEN, "x1");
        ValidationReport report = client.validate(cred(), eth);
        assertFalse(report.accepted());
        assertEquals(KrakenSpotOrderClient.PAIR_UNKNOWN, report.exchangeCode());
        assertTrue(received.isEmpty());
        assertThrows(OrderRejectedException.class, () -> client.submit(cred(), eth));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("Rejet EOrder:* => OrderRejectedException ; clé / signature / droit => TradeCredentialRejectedException")
    void errors() {
        response.set("{\"error\":[\"EOrder:Insufficient funds\"]}");
        assertEquals("EOrder:Insufficient funds", assertThrows(OrderRejectedException.class, () -> client.submit(cred(), request())).getCode());
        response.set("{\"error\":[\"EAPI:Invalid key\"]}");
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        response.set("{\"error\":[\"EGeneral:Permission denied\"]}");
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("Nonce invalide, EService, limite de débit, HTTP 500, réponse illisible => ExchangeUnavailableException")
    void unavailable() {
        for (String body : new String[] {"{\"error\":[\"EAPI:Invalid nonce\"]}", "{\"error\":[\"EService:Unavailable\"]}",
                "{\"error\":[\"EAPI:Rate limit exceeded\"]}", "not json", "{\"foo\":1}"}) {
            response.set(body);
            assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()), body);
        }
        status.set(500);
        response.set("oops");
        assertThrows(ExchangeUnavailableException.class, () -> client.submit(cred(), request()));
    }

    @Test
    @DisplayName("query : ClosedOrders puis OpenOrders par cl_ord_id ; états ; introuvable")
    void query() {
        String id = KrakenSpotOrderClient.krakenClientId("tio1abc");
        response.set("{\"error\":[],\"result\":{\"closed\":{\"OX-1\":{\"status\":\"closed\",\"vol\":\"0.001\",\"vol_exec\":\"0.0004\",\"price\":\"100000\"}}}}");
        OrderState s = client.query(cred(), "BTC-USDC", "tio1abc");
        assertEquals(OrderPhase.CANCELED, s.phase());
        assertEquals("OX-1", s.ordId());
        assertTrue(received.get(0).uri().endsWith("/ClosedOrders"));
        assertTrue(received.get(0).body().contains("cl_ord_id=" + id));
        response.set("{\"error\":[],\"result\":{\"closed\":{\"OX-1\":{\"status\":\"closed\",\"vol\":\"0.001\",\"vol_exec\":\"0.001\",\"price\":\"100000\"}}}}");
        assertEquals(OrderPhase.FILLED, client.query(cred(), "BTC-USDC", "tio1abc").phase());
        response.set("{\"error\":[],\"result\":{\"closed\":{},\"open\":{\"OX-2\":{\"status\":\"open\",\"vol\":\"0.001\",\"vol_exec\":\"0\"}}}}");
        assertEquals(OrderPhase.LIVE, client.query(cred(), "BTC-USDC", "tio1abc").phase());
        response.set("{\"error\":[],\"result\":{\"closed\":{},\"open\":{}}}");
        assertThrows(OrderNotFoundException.class, () -> client.query(cred(), "BTC-USDC", "tio1abc"));
    }

    @Test
    @DisplayName("fills : QueryOrders puis QueryTrades ; frais positif, devise = cotation, identifiant = txid du trade")
    void fills() {
        routes.put("QueryOrders", "{\"error\":[],\"result\":{\"OX-1\":{\"trades\":[\"TR-1\"]}}}");
        routes.put("QueryTrades", "{\"error\":[],\"result\":{\"TR-1\":{\"ordertxid\":\"OX-1\",\"type\":\"buy\",\"price\":\"100000\","
                + "\"vol\":\"0.0001\",\"fee\":\"0.02\",\"time\":1760000000.5}}}");
        List<Fill> fills = client.fills(cred(), "BTC-USDC", "OX-1");
        assertEquals(1, fills.size());
        Fill f = fills.getFirst();
        assertEquals("TR-1", f.tradeId());
        assertEquals(StepSide.BUY, f.side());
        assertEquals(0, new BigDecimal("0.02").compareTo(f.fee()));
        assertEquals("USDC", f.feeCcy());
        assertEquals(Instant.ofEpochMilli(1760000000500L), f.ts());
        assertTrue(received.get(0).body().contains("trades=true"));
        assertTrue(received.get(1).body().contains("txid=TR-1"));
    }

    @Test
    @DisplayName("cancel : par cl_ord_id ; count 0 => OrderNotFoundException")
    void cancel() {
        response.set("{\"error\":[],\"result\":{\"count\":1}}");
        client.cancel(cred(), "BTC-USDC", "tio1abc");
        assertTrue(received.get(0).uri().endsWith("/CancelOrder"));
        response.set("{\"error\":[],\"result\":{\"count\":0}}");
        assertThrows(OrderNotFoundException.class, () -> client.cancel(cred(), "BTC-USDC", "tio1abc"));
    }

    @Test
    @DisplayName("Signature : HMAC-SHA512 du chemin + SHA256(nonce + corps) ; aucun secret dans les exceptions")
    void signatureAndNoSecret() {
        response.set("{\"error\":[\"EAPI:Invalid key\"]}");
        TradeCredentialRejectedException e = assertThrows(TradeCredentialRejectedException.class, () -> client.submit(cred(), request()));
        Received r = received.get(0);
        String nonce = r.body().replaceAll(".*nonce=(\\d+).*", "$1");
        assertEquals(KrakenSignedRequests.sign("/0/private/AddOrder", Long.parseLong(nonce), r.body(), cred().getSecretKey()), r.sign());
        String text = String.valueOf(e.getMessage());
        assertFalse(text.contains(cred().getSecretKey()) || text.contains("kraken-key"));
    }

    @Test
    @DisplayName("Credential incomplète => TradeCredentialRejectedException sans appel")
    void incomplete() {
        ApiCredential bad = ApiCredential.builder().apiKey("k").webProvider(cred().getWebProvider()).build();
        assertThrows(TradeCredentialRejectedException.class, () -> client.submit(bad, request()));
        assertTrue(received.isEmpty());
    }
}
