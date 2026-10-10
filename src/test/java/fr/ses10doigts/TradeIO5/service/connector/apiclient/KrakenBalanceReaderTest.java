package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KrakenApiClient - lecture des soldes disponibles")
class KrakenBalanceReaderTest {

    private static final String BALANCE_EX = """
            {"error":[],"result":{
              "XXBT":{"balance":"0.30","hold_trade":"0.05"},
              "XBT.F":{"balance":"0.10","hold_trade":"0"},
              "XETH":{"balance":"2.0","hold_trade":"0"},
              "PAXG":{"balance":"1.5","hold_trade":"0.5"},
              "USDC":{"balance":"1000","credit":"100","credit_used":"40","hold_trade":"60"},
              "ZEUR":{"balance":"0","hold_trade":"0"},
              "ETH.S":{"balance":"9","hold_trade":"0"},
              "DOT.B":{"balance":"5","hold_trade":"0"}
            }}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> responseBody = new AtomicReference<>(BALANCE_EX);
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private KrakenApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new KrakenApiClient(new FixedDomainClock(Instant.parse("2026-10-09T20:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential credential() {
        return ApiCredential.builder()
                .id(3L)
                .apiKey("krk-key")
                .secretKey(Base64.getEncoder().encodeToString("krk-secret".getBytes(StandardCharsets.UTF_8)))
                .webProvider(WebProvider.builder().code(WebProviderCode.KRAKEN)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .build();
    }

    @Test
    @DisplayName("Normalisation : XXBT/XBT.F -> BTC (agrégés), XETH -> ETH ; staking (.S/.B) ignoré")
    void fetch_normalizesAssets() {
        Map<String, BigDecimal> balances = client.fetchAvailableBalances(credential());

        assertEquals("/0/private/BalanceEx", requestPath.get());
        assertEquals(0, new BigDecimal("0.35").compareTo(balances.get("BTC")));
        assertEquals(0, new BigDecimal("2.0").compareTo(balances.get("ETH")));
        assertFalse(balances.containsKey("DOT"));
        assertFalse(balances.containsKey("XXBT"));
        assertFalse(balances.containsKey("EUR"));
    }

    @Test
    @DisplayName("Solde disponible = balance + credit - credit_used - hold_trade (PAXG, USDC)")
    void fetch_subtractsHoldAndCredit() {
        Map<String, BigDecimal> balances = client.fetchAvailableBalances(credential());

        assertEquals(0, new BigDecimal("1.0").compareTo(balances.get("PAXG")));
        assertEquals(0, new BigDecimal("1000").compareTo(balances.get("USDC")));
    }

    @Test
    @DisplayName("getBalance(BTC/ETH/PAXG/USDC) fonctionne avec les symboles standard")
    void getBalance_standardSymbols() {
        ApiCredential credential = credential();

        assertEquals(0, new BigDecimal("0.35").compareTo(client.getBalance("BTC", credential)));
        assertEquals(0, new BigDecimal("2.0").compareTo(client.getBalance("eth", credential)));
        assertEquals(0, new BigDecimal("1.0").compareTo(client.getBalance("PAXG", credential)));
        assertEquals(0, new BigDecimal("1000").compareTo(client.getBalance("USDC", credential)));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("Erreur API Kraken => BalanceUnavailableException (plus de map vide)")
    void fetch_apiError_throws() {
        responseBody.set("{\"error\":[\"EAPI:Invalid key\"],\"result\":{}}");

        assertThrows(BalanceUnavailableException.class, () -> client.fetchAvailableBalances(credential()));
    }

    @Test
    @DisplayName("Panne réseau / corps illisible => BalanceUnavailableException")
    void fetch_networkOrParsingFailure_throws() {
        responseBody.set("not json");
        assertThrows(BalanceUnavailableException.class, () -> client.fetchAvailableBalances(credential()));

        ApiCredential credential = credential();
        server.stop(0);
        assertThrows(BalanceUnavailableException.class, () -> client.fetchAvailableBalances(credential));
    }

    @Test
    @DisplayName("Compte sans actif (result vide) => map vide valide")
    void fetch_emptyResult_isEmptyMap() {
        responseBody.set("{\"error\":[],\"result\":{}}");

        assertTrue(client.fetchAvailableBalances(credential()).isEmpty());
    }

    @Test
    @DisplayName("Un échec n'est pas mis en cache : l'appel suivant relit l'exchange")
    void getAvailableBalances_failureIsNotCached() {
        ApiCredential credential = credential();
        responseBody.set("{\"error\":[\"EGeneral:Temporary lockout\"],\"result\":{}}");
        assertThrows(BalanceUnavailableException.class, () -> client.getAvailableBalances(credential));

        responseBody.set(BALANCE_EX);
        assertEquals(0, new BigDecimal("2.0").compareTo(client.getAvailableBalances(credential).get("ETH")));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("parseAvailableBalances : champ balance absent ou result absent => exception")
    void parse_missingFields_throw() throws Exception {
        assertThrows(BalanceUnavailableException.class,
                () -> KrakenApiClient.parseAvailableBalances(mapper.readTree("{\"error\":[]}")));
        assertThrows(BalanceUnavailableException.class, () -> KrakenApiClient
                .parseAvailableBalances(mapper.readTree("{\"error\":[],\"result\":{\"XXBT\":{\"hold_trade\":\"0\"}}}")));
    }

    @Test
    @DisplayName("KrakenAssetNames : suffixes et noms historiques")
    void assetNames() {
        assertEquals("BTC", KrakenAssetNames.toSymbol("XXBT").orElseThrow());
        assertEquals("BTC", KrakenAssetNames.toSymbol("XBT").orElseThrow());
        assertEquals("BTC", KrakenAssetNames.toSymbol("XBT.F").orElseThrow());
        assertEquals("ETH", KrakenAssetNames.toSymbol("XETH").orElseThrow());
        assertEquals("ETH", KrakenAssetNames.toSymbol("ETH.F").orElseThrow());
        assertEquals("USD", KrakenAssetNames.toSymbol("ZUSD").orElseThrow());
        assertEquals("PAXG", KrakenAssetNames.toSymbol("PAXG").orElseThrow());
        assertEquals("USDC", KrakenAssetNames.toSymbol("USDC").orElseThrow());
        assertTrue(KrakenAssetNames.toSymbol("ETH.S").isEmpty());
        assertTrue(KrakenAssetNames.toSymbol("DOT.B").isEmpty());
        assertTrue(KrakenAssetNames.toSymbol("XBT.M").isEmpty());
    }
}
