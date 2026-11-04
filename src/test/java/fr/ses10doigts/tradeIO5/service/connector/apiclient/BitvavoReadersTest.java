package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient.Listed;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lecteurs Bitvavo : signature, instruments, carnet, frais, soldes, remplissages (serveur HTTP local, aucun appel réel)")
class BitvavoReadersTest {

    private record Received(String method, String uri, String key, String signature, String timestamp, String window) {
    }

    private static final String KEY = "bv-read-key";
    private static final String SECRET = "bv-read-secret";

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    /** Réponse par chemin (sans requête) ; défaut 404. */
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private int status = 200;
    private final FixedDomainClock clock = new FixedDomainClock(Instant.parse("2026-11-04T00:00:00Z"));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(),
                    ex.getRequestHeaders().getFirst("Bitvavo-Access-Key"), ex.getRequestHeaders().getFirst("Bitvavo-Access-Signature"),
                    ex.getRequestHeaders().getFirst("Bitvavo-Access-Timestamp"), ex.getRequestHeaders().getFirst("Bitvavo-Access-Window")));
            String body = routes.get(ex.getRequestURI().getPath());
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(body == null ? 404 : status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private WebProvider provider(boolean enabled) {
        return WebProvider.builder().code(WebProviderCode.BITVAVO).enabled(enabled)
                .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v2").build();
    }

    private ApiCredential cred() {
        return ApiCredential.builder().apiKey(KEY).secretKey(SECRET).webProvider(provider(true)).build();
    }

    @Test
    @DisplayName("signature : HMAC-SHA256 hex de timestamp+méthode+/v2+chemin+query+corps, en-têtes clé / timestamp / fenêtre")
    void signature() {
        routes.put("/v2/balance", "[]");
        BitvavoRequests.get(cred(), "/balance?x=1");
        Received r = received.getFirst();
        assertEquals(KEY, r.key());
        assertEquals("10000", r.window());
        assertEquals(BitvavoRequests.sign(SECRET, r.timestamp() + "GET/v2/balance?x=1"), r.signature());
        assertEquals(64, r.signature().length());
        assertEquals("/v2", BitvavoRequests.basePath("https://api.bitvavo.com/v2/"));
        assertEquals("", BitvavoRequests.basePath("https://api.bitvavo.com"));
        assertEquals(BitvavoRequests.sign("s", "1POST/v2/order{\"a\":1}"),
                BitvavoRequests.sign("s", BitvavoRequests.stringToSign(1, "POST", "/v2/order", "{\"a\":1}")));
    }

    @Test
    @DisplayName("provider désactivé, clé ou secret absent : aucun appel réseau")
    void incomplete() {
        ApiCredential noSecret = ApiCredential.builder().apiKey(KEY).webProvider(provider(true)).build();
        ApiCredential disabled = ApiCredential.builder().apiKey(KEY).secretKey(SECRET).webProvider(provider(false)).build();
        assertThrows(BalanceUnavailableException.class, () -> new BitvavoBalanceReader(clock).fetchAvailableBalances(noSecret));
        assertThrows(BalanceUnavailableException.class, () -> new BitvavoBalanceReader(clock).fetchAvailableBalances(disabled));
        assertThrows(InstrumentLookupException.class, () -> new BitvavoInstrumentListClient().fetchAll(provider(false)));
        assertThrows(InstrumentLookupException.class, () -> new BitvavoOrderBookClient().fetchBook(provider(false), "BTC-EUR", 5));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("instruments : tick, lot = 10^-quantityDecimals, minima, négociable si trading")
    void instruments() {
        routes.put("/v2/markets", """
                [{"market":"BTC-EUR","status":"trading","base":"BTC","quote":"EUR","tickSize":"1","minOrderInBaseAsset":"0.0001",
                  "minOrderInQuoteAsset":"5","quantityDecimals":8},
                 {"market":"PAXG-EUR","status":"halted","base":"PAXG","quote":"EUR","tickSize":"0.5","quantityDecimals":4},
                 {"market":"BAD-EUR","status":"trading","base":"BAD","quote":"EUR"}]""");
        List<Listed> listed = new BitvavoInstrumentListClient().fetchAll(provider(true));
        assertEquals(2, listed.size());
        Listed btc = listed.getFirst();
        assertEquals("BTC-EUR", btc.instId());
        assertTrue(btc.live());
        assertEquals(0, new BigDecimal("0.00000001").compareTo(btc.lotSz()));
        assertEquals(0, new BigDecimal("1").compareTo(btc.tickSz()));
        assertEquals(0, new BigDecimal("0.0001").compareTo(btc.minSz()));
        assertEquals(0, new BigDecimal("5").compareTo(btc.costMin()));
        assertEquals("BTC-EUR", btc.nativePair());
        Listed paxg = listed.get(1);
        assertFalse(paxg.live());
        assertEquals(0, paxg.lotSz().compareTo(paxg.minSz()));
        assertNull(paxg.costMin());
        assertThrows(InstrumentLookupException.class, () -> BitvavoInstrumentListClient.parse("[]"));
    }

    @Test
    @DisplayName("carnet : paires [prix, quantité], depth transmise ; vide => erreur")
    void book() {
        routes.put("/v2/BTC-EUR/book", "{\"market\":\"BTC-EUR\",\"nonce\":1,\"bids\":[[\"99\",\"1\"],[\"98\",\"2\"]],\"asks\":[[\"101\",\"0.5\"]]}");
        BookResult result = new BitvavoOrderBookClient().fetchBook(provider(true), "btc-eur", 7);
        assertEquals(2, result.book().bids().size());
        assertEquals(0, new BigDecimal("101").compareTo(result.book().asks().getFirst().price()));
        assertEquals("/v2/BTC-EUR/book?depth=7", received.getFirst().uri());
        assertThrows(InstrumentLookupException.class, () -> BitvavoOrderBookClient.parse("{\"bids\":[],\"asks\":[]}"));
        assertThrows(InstrumentLookupException.class, () -> new BitvavoOrderBookClient().fetchBook(provider(true), "../x", 5));
    }

    @Test
    @DisplayName("frais : fractions décimales converties en %, racine ou sous fees ; champ absent => erreur")
    void fees() {
        routes.put("/v2/account/fees", "{\"tier\":\"0\",\"volume\":\"0\",\"taker\":\"0.0025\",\"maker\":\"0.0015\"}");
        BitvavoTradingFeeProvider provider = new BitvavoTradingFeeProvider(clock, Duration.ZERO);
        FeeRate rate = provider.getFeeRate(cred(), "BTC-EUR");
        assertEquals(0, new BigDecimal("0.25").compareTo(rate.takerPct()));
        assertEquals(0, new BigDecimal("0.15").compareTo(rate.makerPct()));
        provider.getFeeRate(cred(), "ETH-EUR");
        assertEquals(1, received.size());
        FeeRate nested = BitvavoTradingFeeProvider.parse(new BitvavoRequests.Reply(200, "{\"fees\":{\"taker\":\"0.002\",\"maker\":\"0.001\"}}"));
        assertEquals(0, new BigDecimal("0.2").compareTo(nested.takerPct()));
        assertThrows(BalanceUnavailableException.class, () -> BitvavoTradingFeeProvider.parse(new BitvavoRequests.Reply(200, "{}")));
    }

    @Test
    @DisplayName("soldes : available > 0 par symbole ; 403 => clé refusée ; erreur HTTP => indisponible")
    void balances() {
        routes.put("/v2/balance", "[{\"symbol\":\"btc\",\"available\":\"0.5\",\"inOrder\":\"0.1\"},{\"symbol\":\"EUR\",\"available\":\"0\",\"inOrder\":\"0\"}]");
        Map<String, BigDecimal> balances = new BitvavoBalanceReader(clock).fetchAvailableBalances(cred());
        assertEquals(1, balances.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(balances.get("BTC")));
        assertThrows(CredentialRejectedException.class,
                () -> BitvavoBalanceReader.parse(new BitvavoRequests.Reply(403, "{\"errorCode\":309,\"error\":\"signature\"}")));
        assertThrows(BalanceUnavailableException.class, () -> BitvavoBalanceReader.parse(new BitvavoRequests.Reply(500, "boom")));
    }

    @Test
    @DisplayName("remplissages : marchés = devises détenues × EUR/USDC/USDT du catalogue ; trade parsé, frais lu tel quel")
    void fills() {
        routes.put("/v2/balance", "[{\"symbol\":\"BTC\",\"available\":\"0.5\",\"inOrder\":\"0\"}]");
        routes.put("/v2/markets", """
                [{"market":"BTC-EUR","base":"BTC","quote":"EUR"},{"market":"BTC-USDC","base":"BTC","quote":"USDC"},
                 {"market":"ETH-EUR","base":"ETH","quote":"EUR"},{"market":"BTC-ETH","base":"BTC","quote":"ETH"}]""");
        routes.put("/v2/trades", """
                [{"id":"t1","orderId":"o1","clientOrderId":"c1","timestamp":1780000000000,"market":"BTC-EUR","side":"sell",
                  "amount":"0.1","price":"100","taker":true,"fee":"0.25","feeCurrency":"EUR","settled":true}]""");
        List<ImportedFill> fills = new BitvavoFillReader(Duration.ZERO).fetchFills(cred(), Instant.parse("2026-01-01T00:00:00Z"));
        assertEquals(2, fills.size());
        ImportedFill fill = fills.getFirst();
        assertEquals("t1", fill.tradeId());
        assertEquals("BTC", fill.base());
        assertEquals("EUR", fill.quote());
        assertEquals(TradeSide.SELL, fill.side());
        assertEquals(0, new BigDecimal("0.25").compareTo(fill.fee()));
        assertEquals("EUR", fill.feeCcy());
        assertEquals("c1", fill.clOrdId());
        assertTrue(received.stream().anyMatch(r -> r.uri().startsWith("/v2/trades?market=BTC-EUR&limit=1000&start=")));
        assertTrue(received.stream().noneMatch(r -> r.uri().contains("ETH-EUR") || r.uri().contains("BTC-ETH")));
        assertNull(BitvavoFillReader.parse(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
    }
}
