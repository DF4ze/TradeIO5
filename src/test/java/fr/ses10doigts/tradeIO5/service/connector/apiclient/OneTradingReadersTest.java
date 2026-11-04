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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lecteurs One Trading : instruments, carnet, frais, soldes, remplissages (serveur HTTP local, aucun appel réel)")
class OneTradingReadersTest {

    private record Received(String method, String uri, String auth) {
    }

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
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().toString(), ex.getRequestHeaders().getFirst("Authorization")));
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
        return WebProvider.builder().code(WebProviderCode.ONE_TRADING).enabled(enabled)
                .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/fast/v1").build();
    }

    private ApiCredential cred() {
        return ApiCredential.builder().id(7L).apiKey("ot-key").webProvider(provider(true)).build();
    }

    @Test
    @DisplayName("instruments : spot actifs, précisions -> lot/tick, min_size -> costMin, futures ignorés")
    void instruments() {
        routes.put("/fast/v1/instruments", """
                [{"id":"BTC_USDC","type":"SPOT","state":"ACTIVE","base":{"code":"BTC","precision":8},"quote":{"code":"USDC","precision":8},
                  "amount_precision":5,"market_precision":2,"min_size":"10.0"},
                 {"id":"ETH_USDC","type":"SPOT","state":"CLOSED","base":{"code":"ETH","precision":8},"quote":{"code":"USDC","precision":8},
                  "amount_precision":4,"market_precision":2,"min_size":"10"},
                 {"id":"BTC_EUR_P","type":"FUTURES","state":"ACTIVE","base":{"code":"BTC"},"quote":{"code":"EUR"}}]""");
        List<Listed> listed = new OneTradingInstrumentListClient().fetchAll(provider(true));
        assertEquals(2, listed.size());
        Listed btc = listed.getFirst();
        assertEquals("BTC-USDC", btc.instId());
        assertTrue(btc.live());
        assertEquals(0, new BigDecimal("0.00001").compareTo(btc.lotSz()));
        assertEquals(0, new BigDecimal("0.01").compareTo(btc.tickSz()));
        assertEquals(0, new BigDecimal("10.0").compareTo(btc.costMin()));
        assertEquals("BTC_USDC", btc.nativePair());
        assertTrue(!listed.get(1).live());
        assertTrue(received.getFirst().auth() == null);
    }

    @Test
    @DisplayName("provider désactivé ou sans URL : aucun appel réseau")
    void disabledProviderDoesNothing() {
        assertThrows(InstrumentLookupException.class, () -> new OneTradingInstrumentListClient().fetchAll(provider(false)));
        assertThrows(InstrumentLookupException.class, () -> new OneTradingOrderBookClient().fetchBook(provider(false), "BTC-USDC", 5));
        ApiCredential disabled = ApiCredential.builder().apiKey("k").webProvider(provider(false)).build();
        assertThrows(BalanceUnavailableException.class, () -> new OneTradingBalanceReader(clock).fetchAvailableBalances(disabled));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("carnet : niveaux price/amount, chemin avec code natif et depth")
    void orderBook() {
        routes.put("/fast/v1/order-book/BTC_USDC", """
                {"instrument_code":"BTC_USDC","bids":[{"price":"99.5","amount":"1"},{"price":"99","amount":"2"}],
                 "asks":[{"price":"100","amount":"3"}]}""");
        BookResult book = new OneTradingOrderBookClient().fetchBook(provider(true), "BTC-USDC", 16);
        assertEquals(2, book.book().bids().size());
        assertEquals(0, new BigDecimal("100").compareTo(book.book().asks().getFirst().price()));
        assertEquals("/fast/v1/order-book/BTC_USDC?level=1&depth=16", received.getFirst().uri());
        routes.put("/fast/v1/order-book/ETH_USDC", "{\"bids\":[],\"asks\":[]}");
        assertThrows(InstrumentLookupException.class, () -> new OneTradingOrderBookClient().fetchBook(provider(true), "ETH-USDC", 5));
    }

    @Test
    @DisplayName("frais : palier SPOT, Bearer, cache entre deux lectures, clé refusée")
    void fees() {
        routes.put("/fast/v1/account/fees", """
                {"account_id":"a","running_volumes":[],"active_fee_tiers":[
                 {"fee_group_id":"FUTURES","volume":0,"maker_fee":"0.0200","taker_fee":"0.0500"},
                 {"fee_group_id":"SPOT","volume":0,"maker_fee":"0.1000","taker_fee":"0.2000"}]}""");
        OneTradingTradingFeeProvider provider = new OneTradingTradingFeeProvider(clock, Duration.ZERO);
        FeeRate rate = provider.getFeeRate(cred(), "BTC-USDC");
        assertEquals(0, new BigDecimal("0.1").compareTo(rate.makerPct()));
        assertEquals(0, new BigDecimal("0.2").compareTo(rate.takerPct()));
        provider.getFeeRate(cred(), "ETH-USDC");
        assertEquals(1, received.size());
        assertEquals("Bearer ot-key", received.getFirst().auth());
        status = 401;
        assertThrows(CredentialRejectedException.class, () -> new OneTradingTradingFeeProvider(clock, Duration.ZERO).getFeeRate(cred(), "BTC-USDC"));
    }

    @Test
    @DisplayName("soldes : available par devise, nuls absents")
    void balances() {
        routes.put("/fast/v1/account/balances", """
                {"account_id":"a","balances":[{"currency_code":"btc","available":"0.5","locked":"0.1"},
                 {"currency_code":"EUR","available":"0","locked":"0"}]}""");
        Map<String, BigDecimal> balances = new OneTradingBalanceReader(clock).fetchAvailableBalances(cred());
        assertEquals(1, balances.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(balances.get("BTC")));
        assertEquals("Bearer ot-key", received.getFirst().auth());
    }

    @Test
    @DisplayName("remplissages : fenêtres de 30 jours, cursor suivi, futures ignorés, frais positif")
    void fills() {
        routes.put("/fast/v1/account/trades", """
                {"trade_history":[
                 {"fee":{"fee_amount":"-0.01","fee_currency":"USDC"},"trade":{"trade_id":"t1","order_id":"o1","instrument_code":"BTC_USDC",
                  "side":"BUY","price":"100","amount":"0.1","time":"2024-01-05T10:00:00.000Z"}},
                 {"fee":{"fee_amount":"0","fee_currency":"EUR"},"trade":{"trade_id":"t2","order_id":"o2","instrument_code":"BTC_EUR_P",
                  "side":"SELL","price":"1","amount":"1","time":"2024-01-05T10:00:00Z"}}],"cursor":null}""");
        List<ImportedFill> fills = new OneTradingFillReader(clock).fetchFills(cred(), null);
        // du 2024-01-01 au 2024-02-10 : deux fenêtres (30 j puis 10 j), une page chacune
        assertEquals(2, received.size());
        assertEquals(2, fills.size());
        ImportedFill fill = fills.getFirst();
        assertEquals("BTC", fill.base());
        assertEquals("USDC", fill.quote());
        assertEquals(TradeSide.BUY, fill.side());
        assertEquals(0, new BigDecimal("0.01").compareTo(fill.fee()));
        assertTrue(received.getFirst().uri().contains("from=2024-01-01T00:00:00Z"));
        assertTrue(received.get(1).uri().contains("from=2024-01-31T00:00:00Z"));
    }
}
