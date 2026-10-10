package fr.ses10doigts.tradeIO5.service.connector.apiclient;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OkxBalanceReader (lecture seule, compte Trading)")
class OkxBalanceReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-09T20:00:00.123Z");

    private static final String BALANCES_BODY = """
            {"code":"0","msg":"","data":[{"totalEq":"100","details":[
              {"ccy":"BTC","availBal":"0.5","cashBal":"0.7","frozenBal":"0.2"},
              {"ccy":"ETH","availBal":"2.25","cashBal":"2.25","frozenBal":"0"},
              {"ccy":"USDC","availBal":"1500.10","cashBal":"1500.10","frozenBal":"0"},
              {"ccy":"OKB","availBal":"0","cashBal":"0","frozenBal":"0"},
              {"ccy":"XRP","availBal":"","cashBal":"0","frozenBal":"0"}
            ]}]}
            """;

    private HttpServer server;
    private final FixedDomainClock clock = new FixedDomainClock(NOW);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> responseBody = new AtomicReference<>(BALANCES_BODY);
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);
    private final List<Map<String, String>> receivedHeaders = new ArrayList<>();
    private final List<String> receivedMethodAndPath = new ArrayList<>();
    private OkxBalanceReader reader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            receivedMethodAndPath.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            receivedHeaders.add(Map.of(
                    "key", exchange.getRequestHeaders().getFirst("OK-ACCESS-KEY"),
                    "sign", exchange.getRequestHeaders().getFirst("OK-ACCESS-SIGN"),
                    "ts", exchange.getRequestHeaders().getFirst("OK-ACCESS-TIMESTAMP"),
                    "pass", exchange.getRequestHeaders().getFirst("OK-ACCESS-PASSPHRASE")));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        reader = new OkxBalanceReader(clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential credential() {
        return ApiCredential.builder()
                .id(7L)
                .apiKey("okx-key")
                .secretKey("okx-secret")
                .passphrase("okx-pass")
                .webProvider(WebProvider.builder().code(WebProviderCode.OKX)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .build();
    }

    @Test
    @DisplayName("Signature HMAC-SHA256 base64 de timestamp+GET+chemin (vecteur indépendant)")
    void sign_matchesReferenceVector() {
        assertEquals("M5UquMzHQxhg1IuROn8eNqeSCfmWFYnaFfuTB+UkecU=",
                OkxBalanceReader.sign("2026-10-09T20:00:00.123Z", "GET", OkxBalanceReader.BALANCE_PATH, "", "okx-secret"));
    }

    @Test
    @DisplayName("Requête : GET /api/v5/account/balance, en-têtes OK-ACCESS-*, timestamp ISO ms issu de DomainClock")
    void fetch_sendsSignedReadOnlyRequest() {
        reader.fetchAvailableBalances(credential());

        assertEquals(1, calls.get());
        assertEquals("GET /api/v5/account/balance", receivedMethodAndPath.getFirst());
        Map<String, String> headers = receivedHeaders.getFirst();
        assertEquals("okx-key", headers.get("key"));
        assertEquals("okx-pass", headers.get("pass"));
        assertEquals("2026-10-09T20:00:00.123Z", headers.get("ts"));
        assertEquals("M5UquMzHQxhg1IuROn8eNqeSCfmWFYnaFfuTB+UkecU=", headers.get("sign"));
    }

    @Test
    @DisplayName("Timestamp : millisecondes toujours présentes (même .000)")
    void fetch_timestampKeepsMilliseconds() {
        clock.set(Instant.parse("2026-10-09T20:00:00Z"));

        reader.fetchAvailableBalances(credential());

        assertEquals("2026-10-09T20:00:00.000Z", receivedHeaders.getFirst().get("ts"));
    }

    @Test
    @DisplayName("Parsing : solde disponible (availBal) par devise, soldes nuls/vides absents")
    void fetch_parsesAvailableBalances() {
        Map<String, BigDecimal> balances = reader.fetchAvailableBalances(credential());

        assertEquals(3, balances.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(balances.get("BTC")));
        assertEquals(0, new BigDecimal("2.25").compareTo(balances.get("ETH")));
        assertEquals(0, new BigDecimal("1500.10").compareTo(balances.get("USDC")));
    }

    @Test
    @DisplayName("Réponse valide sans aucun solde => map vide, pas une erreur")
    void fetch_emptyDetails_isEmptyMapNotError() {
        responseBody.set("{\"code\":\"0\",\"msg\":\"\",\"data\":[{\"details\":[]}]}");

        assertTrue(reader.fetchAvailableBalances(credential()).isEmpty());
    }

    @Test
    @DisplayName("Code d'erreur API (HTTP 200) => BalanceUnavailableException")
    void fetch_apiErrorCode_throws() {
        responseBody.set("{\"code\":\"50102\",\"msg\":\"Timestamp request expired\",\"data\":[]}");

        BalanceUnavailableException e = assertThrows(BalanceUnavailableException.class,
                () -> reader.fetchAvailableBalances(credential()));
        assertTrue(e.getMessage().contains("50102"));
    }

    @Test
    @DisplayName("HTTP 401 avec corps d'erreur OKX => BalanceUnavailableException portant le code")
    void fetch_http401WithJsonBody_throwsWithCode() {
        responseStatus.set(401);
        responseBody.set("{\"code\":\"50113\",\"msg\":\"Invalid Sign\",\"data\":[]}");

        BalanceUnavailableException e = assertThrows(BalanceUnavailableException.class,
                () -> reader.fetchAvailableBalances(credential()));
        assertTrue(e.getMessage().contains("50113"));
    }

    @Test
    @DisplayName("HTTP 502 non JSON, JSON sans details, valeur illisible => BalanceUnavailableException")
    void fetch_malformedResponses_throw() {
        responseStatus.set(502);
        responseBody.set("<html>Bad gateway</html>");
        assertThrows(BalanceUnavailableException.class, () -> reader.fetchAvailableBalances(credential()));

        responseStatus.set(200);
        responseBody.set("{\"code\":\"0\",\"msg\":\"\",\"data\":[]}");
        assertThrows(BalanceUnavailableException.class, () -> reader.fetchAvailableBalances(credential()));

        responseBody.set("{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"BTC\",\"availBal\":\"abc\"}]}]}");
        assertThrows(BalanceUnavailableException.class, () -> reader.fetchAvailableBalances(credential()));
    }

    @Test
    @DisplayName("Serveur injoignable => BalanceUnavailableException")
    void fetch_unreachableServer_throws() {
        ApiCredential credential = credential();
        server.stop(0);

        assertThrows(BalanceUnavailableException.class, () -> reader.fetchAvailableBalances(credential));
    }

    @Test
    @DisplayName("Credential incomplète (passphrase absente) => exception, aucun appel réseau")
    void fetch_incompleteCredential_throwsWithoutCall() {
        ApiCredential credential = credential();
        credential.setPassphrase(" ");

        assertThrows(BalanceUnavailableException.class, () -> reader.fetchAvailableBalances(credential));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("Cache : un seul appel dans le TTL, nouvel appel après expiration")
    void getAvailableBalances_cachesWithinTtl() {
        ApiCredential credential = credential();

        reader.getAvailableBalances(credential);
        clock.advance(Duration.ofSeconds(30));
        reader.getAvailableBalances(credential);
        assertEquals(1, calls.get());

        clock.advance(Duration.ofSeconds(31));
        reader.getAvailableBalances(credential);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("Cache : un échec n'est jamais mis en cache")
    void getAvailableBalances_failureIsNotCached() {
        ApiCredential credential = credential();
        responseBody.set("{\"code\":\"50011\",\"msg\":\"Rate limit reached\",\"data\":[]}");
        assertThrows(BalanceUnavailableException.class, () -> reader.getAvailableBalances(credential));

        responseBody.set(BALANCES_BODY);
        assertEquals(3, reader.getAvailableBalances(credential).size());
        assertEquals(2, calls.get());
    }
}
