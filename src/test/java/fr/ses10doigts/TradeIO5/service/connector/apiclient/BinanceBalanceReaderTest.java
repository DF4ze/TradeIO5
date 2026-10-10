package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BinanceApiClient - lecture des soldes disponibles (free)")
class BinanceBalanceReaderTest {

    private final BinanceApiClient client = new BinanceApiClient(new FixedDomainClock(Instant.parse("2026-10-09T20:00:00Z")));

    @Test
    @DisplayName("parseFreeBalances : solde 'free' > 0 uniquement")
    void parse_keepsPositiveFreeOnly() {
        Map<String, BigDecimal> balances = BinanceApiClient.parseFreeBalances("""
                {"balances":[{"asset":"BTC","free":"0.5","locked":"0.1"},
                             {"asset":"ETH","free":"0.00000000","locked":"3"}]}
                """);

        assertEquals(1, balances.size());
        assertEquals(0, new BigDecimal("0.5").compareTo(balances.get("BTC")));
        assertFalse(balances.containsKey("ETH"));
    }

    @Test
    @DisplayName("Simple Earn : LDBTC agrégé à BTC, LDO (Lido DAO) conservé tel quel")
    void parse_mergesEarnTokens() {
        Map<String, BigDecimal> balances = BinanceApiClient.parseFreeBalances("""
                {"balances":[{"asset":"BTC","free":"0.5","locked":"0"},
                             {"asset":"LDBTC","free":"0.25","locked":"0"},
                             {"asset":"LDUSDC","free":"100","locked":"0"},
                             {"asset":"LDO","free":"7","locked":"0"}]}
                """);

        assertEquals(0, new BigDecimal("0.75").compareTo(balances.get("BTC")));
        assertEquals(0, new BigDecimal("100").compareTo(balances.get("USDC")));
        assertEquals(0, new BigDecimal("7").compareTo(balances.get("LDO")));
        assertFalse(balances.containsKey("LDBTC"));
    }

    @Test
    @DisplayName("USDT : présent, LDUSDT agrégé à USDT, nul/absent ignoré")
    void parse_usdt() {
        Map<String, BigDecimal> balances = BinanceApiClient.parseFreeBalances("""
                {"balances":[{"asset":"USDT","free":"20","locked":"0"},
                             {"asset":"LDUSDT","free":"5","locked":"0"},
                             {"asset":"USDC","free":"0","locked":"0"}]}
                """);

        assertEquals(0, new BigDecimal("25").compareTo(balances.get("USDT")));
        assertFalse(balances.containsKey("USDC"));
    }

    @Test
    @DisplayName("Credential absente ou erreur API (HTTP 401) => BalanceUnavailableException")
    void fetch_failures_throw() throws IOException {
        assertThrows(BalanceUnavailableException.class, () -> client.fetchAvailableBalances(null));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "{\"code\":-2015,\"msg\":\"Invalid API-key, IP, or permissions for action.\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ApiCredential credential = ApiCredential.builder().id(1L).apiKey("k").secretKey("s")
                    .webProvider(WebProvider.builder().code(WebProviderCode.BINANCE)
                            .apiBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
                    .build();
            assertThrows(BalanceUnavailableException.class, () -> client.fetchAvailableBalances(credential));
        } finally {
            server.stop(0);
        }
    }
}
