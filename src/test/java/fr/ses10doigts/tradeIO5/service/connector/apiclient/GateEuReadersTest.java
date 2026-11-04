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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lecteurs Gate EU : signature, instruments, carnet, frais, soldes, remplissages (serveur HTTP local, aucun appel réel)")
class GateEuReadersTest {

    private record Received(String method, String path, String query, String key, String timestamp, String sign) {
    }

    private static final String SECRET = "sekret";

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    /** Réponse par chemin (sans requête) ; défaut 404. */
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private int status = 200;
    private final FixedDomainClock clock = new FixedDomainClock(Instant.parse("2024-02-10T00:00:00Z"));

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery() == null ? ""
                    : ex.getRequestURI().getRawQuery(), ex.getRequestHeaders().getFirst("KEY"),
                    ex.getRequestHeaders().getFirst("Timestamp"), ex.getRequestHeaders().getFirst("SIGN")));
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
        return WebProvider.builder().code(WebProviderCode.GATE_EU).enabled(enabled)
                .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4").build();
    }

    private ApiCredential cred() {
        return ApiCredential.builder().id(7L).apiKey("gate-key").secretKey(SECRET).webProvider(provider(true)).build();
    }

    @Test
    @DisplayName("signature : chaîne METHOD/chemin/query/SHA512(corps)/timestamp et HMAC-SHA512 hexadécimal (vecteurs indépendants)")
    void signature() {
        String body = "{\"text\":\"t-a\"}";
        assertEquals("2236a957b4c2d20c086c337e3755bd42697fa15fb6fb093b37c6fb5136dbb82bcccb982f6c0f8f6919522b61fb0d0bd93d777b970a43abcff37af89c861030cf",
                GateEuRequests.sha512Hex(body));
        String message = GateEuRequests.stringToSign("POST", "/api/v4/spot/orders", "foo=bar", body, 1700000000L);
        assertEquals("POST\n/api/v4/spot/orders\nfoo=bar\n" + GateEuRequests.sha512Hex(body) + "\n1700000000", message);
        assertEquals("436c132cca81f3f0877d1204f7db5eb6c4b8c0e0c5650388085099783e2b6d7a856171bfc1ecf833988e876d777eaec3603b47d64405a4eff9de1cdeea1fd5f9",
                GateEuRequests.sign(SECRET, message));
        assertTrue(GateEuRequests.stringToSign("GET", "/api/v4/spot/accounts", "", null, 1L)
                .contains("cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"));
        assertEquals("/api/v4", GateEuRequests.basePath("https://api.gateeu.com/api/v4/"));
        assertEquals("BTC_EUR", GateEuRequests.nativePair("btc-eur"));
    }

    @Test
    @DisplayName("requête privée : en-têtes KEY / Timestamp / SIGN, SIGN recalculable avec le chemin complet et la query")
    void privateHeaders() {
        routes.put("/api/v4/spot/accounts", "[]");
        GateEuRequests.get(cred(), "/spot/accounts?currency=BTC");
        Received r = received.getFirst();
        assertEquals("gate-key", r.key());
        assertEquals(GateEuRequests.sign(SECRET, GateEuRequests.stringToSign("GET", "/api/v4/spot/accounts", "currency=BTC", "",
                Long.parseLong(r.timestamp()))), r.sign());
    }

    @Test
    @DisplayName("instruments : tradable, précisions -> lot/tick, min_quote_amount -> costMin, paire native")
    void instruments() {
        routes.put("/api/v4/spot/currency_pairs", """
                [{"id":"BTC_EUR","base":"BTC","quote":"EUR","amount_precision":5,"precision":2,"min_base_amount":"0.0001","min_quote_amount":"1","trade_status":"tradable"},
                 {"id":"ETH_EUR","base":"ETH","quote":"EUR","amount_precision":4,"precision":2,"trade_status":"untradable"}]""");
        List<Listed> listed = new GateEuInstrumentListClient().fetchAll(provider(true));
        assertEquals(2, listed.size());
        Listed btc = listed.getFirst();
        assertEquals("BTC-EUR", btc.instId());
        assertTrue(btc.live());
        assertEquals(0, new BigDecimal("0.00001").compareTo(btc.lotSz()));
        assertEquals(0, new BigDecimal("0.01").compareTo(btc.tickSz()));
        assertEquals(0, new BigDecimal("0.0001").compareTo(btc.minSz()));
        assertEquals(0, BigDecimal.ONE.compareTo(btc.costMin()));
        assertEquals("BTC_EUR", btc.nativePair());
        Listed eth = listed.get(1);
        assertFalse(eth.live());
        assertEquals(0, new BigDecimal("0.0001").compareTo(eth.minSz()));
        routes.put("/api/v4/spot/currency_pairs", "[]");
        assertThrows(InstrumentLookupException.class, () -> new GateEuInstrumentListClient().fetchAll(provider(true)));
    }

    @Test
    @DisplayName("provider désactivé : aucun appel réseau")
    void disabledProvider() {
        assertThrows(InstrumentLookupException.class, () -> new GateEuInstrumentListClient().fetchAll(provider(false)));
        assertThrows(InstrumentLookupException.class, () -> new GateEuOrderBookClient().fetchBook(provider(false), "BTC-EUR", 5));
        ApiCredential disabled = ApiCredential.builder().apiKey("k").secretKey("s").webProvider(provider(false)).build();
        assertThrows(BalanceUnavailableException.class, () -> new GateEuBalanceReader(clock).fetchAvailableBalances(disabled));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("carnet : [prix, quantité], profondeur plafonnée à 100, vide => erreur")
    void orderBook() {
        routes.put("/api/v4/spot/order_book", """
                {"asks":[["101","2"],["102","3"]],"bids":[["100","1.5"],["99","4"]]}""");
        BookResult book = new GateEuOrderBookClient().fetchBook(provider(true), "BTC-EUR", 500);
        assertEquals(2, book.book().asks().size());
        assertEquals(0, new BigDecimal("100").compareTo(book.book().bids().getFirst().price()));
        assertEquals(0, new BigDecimal("1.5").compareTo(book.book().bids().getFirst().quantity()));
        assertEquals("currency_pair=BTC_EUR&limit=100", received.getFirst().query());
        routes.put("/api/v4/spot/order_book", "{\"asks\":[],\"bids\":[]}");
        assertThrows(InstrumentLookupException.class, () -> new GateEuOrderBookClient().fetchBook(provider(true), "BTC-EUR", 5));
    }

    @Test
    @DisplayName("frais : /wallet/fee en fractions -> pourcentages, mis en cache ; champ illisible => erreur")
    void fees() {
        routes.put("/api/v4/wallet/fee", "{\"maker_fee\":\"0.002\",\"taker_fee\":\"0.0025\"}");
        GateEuTradingFeeProvider provider = new GateEuTradingFeeProvider(clock, Duration.ZERO);
        FeeRate rate = provider.getFeeRate(cred(), "BTC-EUR");
        assertEquals(0, new BigDecimal("0.2").compareTo(rate.makerPct()));
        assertEquals(0, new BigDecimal("0.25").compareTo(rate.takerPct()));
        assertEquals("currency_pair=BTC_EUR", received.getFirst().query());
        provider.getFeeRate(cred(), "BTC-EUR");
        assertEquals(1, received.size());
        routes.put("/api/v4/wallet/fee", "{\"maker_fee\":\"abc\",\"taker_fee\":\"0.1\"}");
        assertThrows(BalanceUnavailableException.class, () -> new GateEuTradingFeeProvider(clock, Duration.ZERO).getFeeRate(cred(), "ETH-EUR"));
    }

    @Test
    @DisplayName("soldes : available par devise, soldes nuls absents ; 401 => clé refusée")
    void balances() {
        routes.put("/api/v4/spot/accounts", "[{\"currency\":\"btc\",\"available\":\"0.5\",\"locked\":\"0\"},{\"currency\":\"EUR\",\"available\":\"0\"}]");
        Map<String, BigDecimal> balances = new GateEuBalanceReader(clock).fetchAvailableBalances(cred());
        assertEquals(1, balances.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(balances.get("BTC")));
        status = 401;
        assertThrows(CredentialRejectedException.class, () -> new GateEuBalanceReader(clock).fetchAvailableBalances(cred()));
    }

    @Test
    @DisplayName("remplissages : fenêtres de 30 jours, from/to en secondes, frais en coût positif, côté et devise")
    void fills() {
        routes.put("/api/v4/spot/my_trades", """
                [{"id":"9","create_time":"1707000000","create_time_ms":"1707000000123.4","currency_pair":"BTC_EUR","order_id":"77","side":"sell",
                  "amount":"0.1","price":"40000","fee":"-1.2","fee_currency":"eur"}]""");
        List<ImportedFill> fills = new GateEuFillReader(clock).fetchFills(cred(), null);
        assertEquals(2, received.size());
        assertTrue(received.getFirst().query().startsWith("from=" + GateEuFillReader.EARLIEST.getEpochSecond() + "&to="));
        ImportedFill fill = fills.getFirst();
        assertEquals("9", fill.tradeId());
        assertEquals("77", fill.ordId());
        assertEquals(TradeSide.SELL, fill.side());
        assertEquals("BTC", fill.base());
        assertEquals("EUR", fill.quote());
        assertEquals(0, new BigDecimal("1.2").compareTo(fill.fee()));
        assertEquals("EUR", fill.feeCcy());
        assertEquals(Instant.ofEpochMilli(1707000000123L), fill.ts());
    }
}
