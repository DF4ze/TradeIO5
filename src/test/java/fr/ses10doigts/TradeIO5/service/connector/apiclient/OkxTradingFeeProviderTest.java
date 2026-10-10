package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("OkxTradingFeeProvider : frais réels du compte (trade-fee, clé Read)")
class OkxTradingFeeProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00.123Z");

    private static final String FEE_BODY = """
            {"code":"0","msg":"","data":[{"category":"1","delivery":"","exercise":"","instType":"SPOT","level":"Lv1",
              "maker":"-0.0008","taker":"-0.001","makerU":"","takerU":"","makerUSDC":"-0.0004","takerUSDC":"-0.0007",
              "ts":"1700000000000","fiat":[]}]}
            """;

    private HttpServer server;
    private final FixedDomainClock clock = new FixedDomainClock(NOW);
    private final AtomicReference<String> responseBody = new AtomicReference<>(FEE_BODY);
    private final List<String> requests = new ArrayList<>();
    private final List<Map<String, String>> headers = new ArrayList<>();
    private OkxTradingFeeProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            headers.add(Map.of("sign", exchange.getRequestHeaders().getFirst("OK-ACCESS-SIGN"),
                    "ts", exchange.getRequestHeaders().getFirst("OK-ACCESS-TIMESTAMP"),
                    "key", exchange.getRequestHeaders().getFirst("OK-ACCESS-KEY")));
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        provider = new OkxTradingFeeProvider(clock, Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ApiCredential credential() {
        return ApiCredential.builder().id(7L).apiKey("okx-key").secretKey("okx-secret").passphrase("okx-pass")
                .webProvider(WebProvider.builder().code(WebProviderCode.OKX)
                        .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                .build();
    }

    private static void assertPct(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "attendu " + expected + " obtenu " + actual);
    }

    @Test
    @DisplayName("Paire USDT : maker/taker signés négatifs (frais payés) => % positifs ; requête signée avec la query")
    void usdtPair() {
        FeeRate rate = provider.getFeeRate(credential(), "PAXG-USDT");

        assertPct("0.08", rate.makerPct());
        assertPct("0.1", rate.takerPct());
        assertEquals(List.of("GET /api/v5/account/trade-fee?instType=SPOT&instId=PAXG-USDT"), requests);
        assertEquals("okx-key", headers.getFirst().get("key"));
        assertEquals("2026-10-10T10:00:00.123Z", headers.getFirst().get("ts"));
        assertEquals(OkxSignedRequests.sign("2026-10-10T10:00:00.123Z", "GET",
                        "/api/v5/account/trade-fee?instType=SPOT&instId=PAXG-USDT", "", "okx-secret"),
                headers.getFirst().get("sign"));
    }

    @Test
    @DisplayName("Paire USDC : champs makerUSDC/takerUSDC")
    void usdcPair() {
        FeeRate rate = provider.getFeeRate(credential(), "BTC-USDC");

        assertPct("0.04", rate.makerPct());
        assertPct("0.07", rate.takerPct());
    }

    @Test
    @DisplayName("Rebate (taux positif) => coût négatif")
    void rebate() {
        responseBody.set("{\"code\":\"0\",\"data\":[{\"maker\":\"0.0002\",\"taker\":\"-0.0005\"}]}");

        FeeRate rate = provider.getFeeRate(credential(), "BTC-USDT");

        assertPct("-0.02", rate.makerPct());
        assertPct("0.05", rate.takerPct());
    }

    @Test
    @DisplayName("Cache 24 h sur DomainClock : pas de 2e appel, puis nouvel appel après expiration")
    void cache() {
        provider.getFeeRate(credential(), "PAXG-USDT");
        provider.getFeeRate(credential(), "PAXG-USDT");
        assertEquals(1, requests.size());

        provider.getFeeRate(credential(), "BTC-USDT");
        assertEquals(2, requests.size());

        clock.advance(Duration.ofHours(25));
        provider.getFeeRate(credential(), "PAXG-USDT");
        assertEquals(3, requests.size());
    }

    @Test
    @DisplayName("Échecs non cachés ; clé rejetée => CredentialRejectedException sans secret dans le message")
    void failuresNotCached() {
        responseBody.set("{\"code\":\"50113\",\"msg\":\"Invalid Sign\",\"data\":[]}");
        CredentialRejectedException e = assertThrows(CredentialRejectedException.class,
                () -> provider.getFeeRate(credential(), "PAXG-USDT"));
        assertFalse(e.getMessage().contains("okx-secret"));
        assertFalse(e.getMessage().contains("okx-pass"));

        responseBody.set(FEE_BODY);
        assertPct("0.1", provider.getFeeRate(credential(), "PAXG-USDT").takerPct());
        assertEquals(2, requests.size());
    }

    @Test
    @DisplayName("Erreur API générique, champ absent/illisible, réponse vide => BalanceUnavailableException (jamais de frais supposé)")
    void unavailable() {
        responseBody.set("{\"code\":\"50011\",\"msg\":\"Too many requests\",\"data\":[]}");
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(credential(), "PAXG-USDT"));

        responseBody.set("{\"code\":\"0\",\"data\":[]}");
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(credential(), "PAXG-USDT"));

        responseBody.set("{\"code\":\"0\",\"data\":[{\"maker\":\"-0.0008\",\"taker\":\"\"}]}");
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(credential(), "PAXG-USDT"));

        responseBody.set("{\"code\":\"0\",\"data\":[{\"maker\":\"-0.0008\",\"taker\":\"abc\"}]}");
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(credential(), "PAXG-USDT"));

        responseBody.set("{\"code\":\"0\",\"data\":[{\"maker\":\"-0.0008\",\"taker\":\"-0.001\"}]}");
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(credential(), "BTC-USDC"));

        responseBody.set("");
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(credential(), "PAXG-USDT"));
    }

    @Test
    @DisplayName("Credential incomplète => BalanceUnavailableException, aucun appel réseau")
    void incompleteCredential() {
        ApiCredential incomplete = ApiCredential.builder().id(8L).apiKey("k").build();
        assertThrows(BalanceUnavailableException.class, () -> provider.getFeeRate(incomplete, "PAXG-USDT"));
        assertEquals(0, requests.size());
    }
}
