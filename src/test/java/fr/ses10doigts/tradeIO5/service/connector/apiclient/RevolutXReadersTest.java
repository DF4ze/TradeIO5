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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lecteurs Revolut X : paires, carnet, frais, soldes, remplissages, signature Ed25519 (serveur HTTP local, aucun appel réel)")
class RevolutXReadersTest {

    private record Received(String method, String path, String query, String key, String timestamp, String signature) {
    }

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    /** Réponse par chemin (sans requête) ; défaut 404. */
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private int status = 200;
    private KeyPair keys;
    private final FixedDomainClock clock = new FixedDomainClock(Instant.parse("2026-11-04T00:00:00Z"));

    @BeforeEach
    void setUp() throws Exception {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            received.add(new Received(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    ex.getRequestHeaders().getFirst("X-Revx-API-Key"), ex.getRequestHeaders().getFirst("X-Revx-Timestamp"),
                    ex.getRequestHeaders().getFirst("X-Revx-Signature")));
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
        return WebProvider.builder().code(WebProviderCode.REVOLUT_X).enabled(enabled)
                .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/1.0").build();
    }

    private String pem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(keys.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private ApiCredential cred(boolean enabled) {
        return ApiCredential.builder().id(7L).apiKey("rx-key").secretKey(pem()).webProvider(provider(enabled)).build();
    }

    private boolean signatureValid(Received r, String body) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keys.getPublic());
        verifier.update((r.timestamp() + r.method() + r.path() + (r.query() == null ? "" : r.query()) + body).getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(r.signature()));
    }

    @Test
    @DisplayName("paires : actives -> lot/tick/minimums, région EEA, appel public sans en-tête d'authentification")
    void instruments() {
        routes.put("/api/1.0/public/configuration/pairs", """
                {"BTC/USD":{"base":"BTC","quote":"USD","base_step":"0.00001","quote_step":"0.01","min_order_size":"0.0001",
                  "max_order_size":"100","min_order_size_quote":"1","status":"active"},
                 "ETH/EUR":{"base":"ETH","quote":"EUR","base_step":"0.0001","quote_step":"0.01","min_order_size":"0.001",
                  "max_order_size":"1000","min_order_size_quote":"1","status":"inactive"}}""");
        List<Listed> listed = new RevolutXInstrumentListClient().fetchAll(provider(true));
        assertEquals(2, listed.size());
        Listed btc = listed.getFirst();
        assertEquals("BTC-USD", btc.instId());
        assertTrue(btc.live());
        assertEquals(0, new BigDecimal("0.00001").compareTo(btc.lotSz()));
        assertEquals(0, new BigDecimal("0.01").compareTo(btc.tickSz()));
        assertEquals(0, new BigDecimal("0.0001").compareTo(btc.minSz()));
        assertEquals(0, BigDecimal.ONE.compareTo(btc.costMin()));
        assertEquals("BTC-USD", btc.nativePair());
        assertFalse(listed.get(1).live());
        assertEquals("region=EEA", received.getFirst().query());
        assertNull(received.getFirst().key());
    }

    @Test
    @DisplayName("provider désactivé : aucun appel réseau")
    void disabledProviderDoesNothing() {
        assertThrows(InstrumentLookupException.class, () -> new RevolutXInstrumentListClient().fetchAll(provider(false)));
        assertThrows(InstrumentLookupException.class, () -> new RevolutXOrderBookClient().fetchBook(provider(false), "BTC-USD", 5));
        assertThrows(BalanceUnavailableException.class, () -> new RevolutXBalanceReader(clock).fetchAvailableBalances(cred(false)));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("carnet : niveaux p/q (forme authentifiée et noms longs), chemin v2 avec limit borné et région")
    void orderBook() {
        routes.put("/api/2.0/public/order-book/BTC-USD", """
                {"data":{"asks":[{"p":"100","q":"3"}],"bids":[{"p":"99.5","q":"1"},{"price":"99","quantity":"2"}]},"metadata":{"timestamp":1}}""");
        BookResult book = new RevolutXOrderBookClient().fetchBook(provider(true), "btc-usd", 1000);
        assertEquals(2, book.book().bids().size());
        assertEquals(0, new BigDecimal("100").compareTo(book.book().asks().getFirst().price()));
        assertEquals("limit=192&region=EEA", received.getFirst().query());
        routes.put("/api/2.0/public/order-book/ETH-USD", "{\"data\":{\"asks\":[],\"bids\":[]}}");
        assertThrows(InstrumentLookupException.class, () -> new RevolutXOrderBookClient().fetchBook(provider(true), "ETH-USD", 5));
    }

    @Test
    @DisplayName("soldes : disponible par devise, requête signée Ed25519 vérifiable, clé refusée")
    void balances() throws Exception {
        routes.put("/api/1.0/balances", "[{\"currency\":\"btc\",\"available\":\"0.5\",\"reserved\":\"0\",\"total\":\"0.5\"},"
                + "{\"currency\":\"USD\",\"available\":\"0\",\"reserved\":\"1\",\"total\":\"1\"}]");
        Map<String, BigDecimal> balances = new RevolutXBalanceReader(clock).fetchAvailableBalances(cred(true));
        assertEquals(1, balances.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(balances.get("BTC")));
        Received r = received.getFirst();
        assertEquals("rx-key", r.key());
        assertEquals("/api/1.0/balances", r.path());
        assertTrue(signatureValid(r, ""));
        status = 401;
        assertThrows(CredentialRejectedException.class, () -> new RevolutXBalanceReader(clock).fetchAvailableBalances(cred(true)));
    }

    @Test
    @DisplayName("clé privée illisible : CredentialRejectedException, aucun appel")
    void badPrivateKey() {
        ApiCredential bad = ApiCredential.builder().apiKey("k").secretKey("pas une clé").webProvider(provider(true)).build();
        assertThrows(CredentialRejectedException.class, () -> new RevolutXBalanceReader(clock).fetchAvailableBalances(bad));
        assertTrue(received.isEmpty());
    }

    @Test
    @DisplayName("frais : palier spot courant de fees/tiers (fractions -> %), mis en cache, jamais supposés")
    void fees() {
        routes.put("/api/1.0/configuration/fees/tiers", """
                {"data":[{"type":"futures","current_tier":0,"currency":"USD","tiers":[{"tier":0,"maker_fee":"0.0001","taker_fee":"0.0009"}]},
                 {"type":"spot","current_tier":1,"currency":"USD","tiers":[
                   {"tier":0,"volume_from":"0","volume_to":"100","maker_fee":"0","taker_fee":"0.0010"},
                   {"tier":1,"volume_from":"100","maker_fee":"0","taker_fee":"0.0009"}]}],"metadata":{"timestamp":1}}""");
        RevolutXTradingFeeProvider provider = new RevolutXTradingFeeProvider(clock);
        FeeRate rate = provider.getFeeRate(cred(true), "BTC-USDC");
        assertEquals(0, BigDecimal.ZERO.compareTo(rate.makerPct()));
        assertEquals(0, new BigDecimal("0.09").compareTo(rate.takerPct()));
        assertEquals("/api/1.0/configuration/fees/tiers", received.getFirst().path());
        provider.getFeeRate(cred(true), "ETH-USDC");
        assertEquals(1, received.size());
    }

    @Test
    @DisplayName("frais : palier spot absent ou réponse en erreur => indisponible")
    void feesUnavailable() {
        routes.put("/api/1.0/configuration/fees/tiers", "{\"data\":[{\"type\":\"futures\",\"current_tier\":0,\"tiers\":[]}]}");
        assertThrows(BalanceUnavailableException.class, () -> new RevolutXTradingFeeProvider(clock).getFeeRate(cred(true), "BTC-USDC"));
        status = 500;
        assertThrows(BalanceUnavailableException.class, () -> new RevolutXTradingFeeProvider(clock).getFeeRate(cred(true), "BTC-USDC"));
    }

    @Test
    @DisplayName("remplissages : ordres exécutés -> détail (frais) + fills, tid conservé, frais sur le premier remplissage")
    void fills() {
        routes.put("/api/1.0/orders/historical", """
                {"data":[{"id":"11111111-1111-1111-1111-111111111111","client_order_id":"cid-1","symbol":"BTC-USD","side":"buy",
                  "filled_quantity":"0.003","quantity":"0.003","status":"filled"},
                 {"id":"22222222-2222-2222-2222-222222222222","client_order_id":"cid-2","symbol":"BTC-USD","side":"buy",
                  "filled_quantity":"0","quantity":"0.003","status":"cancelled"}],"metadata":{"timestamp":1}}""");
        routes.put("/api/1.0/orders/11111111-1111-1111-1111-111111111111", "{\"data\":{\"total_fee\":\"0.27\",\"fee_currency\":\"USD\"}}");
        routes.put("/api/1.0/orders/fills/11111111-1111-1111-1111-111111111111", """
                {"data":[{"tdt":1762000000000,"aid":"BTC","pc":"USD","p":"90000","q":"0.001","tid":"t-1","oid":"x","s":"buy","im":false},
                         {"tdt":1762000001000,"aid":"BTC","pc":"USD","p":"90010","q":"0.002","tid":"t-2","oid":"x","s":"buy","im":false}]}""");
        List<ImportedFill> fills = new RevolutXFillReader(clock).fetchFills(cred(true), Instant.parse("2026-10-25T00:00:00Z"));
        assertEquals(2, fills.size());
        ImportedFill first = fills.getFirst();
        assertEquals("t-1", first.tradeId());
        assertEquals("cid-1", first.clOrdId());
        assertEquals(TradeSide.BUY, first.side());
        assertEquals("BTC", first.base());
        assertEquals("USD", first.quote());
        assertEquals(0, new BigDecimal("0.27").compareTo(first.fee()));
        assertEquals(0, BigDecimal.ZERO.compareTo(fills.get(1).fee()));
        assertTrue(received.stream().anyMatch(r -> "/api/1.0/orders/historical".equals(r.path())
                && r.query().startsWith("order_states=filled,cancelled&start_date=")));
        assertEquals(3, received.size());
    }
}
