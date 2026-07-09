package fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Le format de réponse Twelve Data exact n'a pas pu être vérifié contre un appel réel (aucune clé
 * disponible au moment de cette implémentation, cf. prompt d'implémentation Lot 2, item E) : ces
 * tests figent le comportement du parsing défensif ({@link TwelveDataQuoteClient#parsePrices}/
 * {@link TwelveDataQuoteClient#parseQuotes}) sur la forme documentée par le comportement du client
 * officiel Twelve Data. <b>À revalider dès qu'une clé réelle est disponible</b> — voir avertissement
 * en tête de {@link TwelveDataQuoteClient}.
 */
@DisplayName("Indicator External - TwelveDataQuoteClient")
class TwelveDataQuoteClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ApiCredentialDTO credentialFor(HttpServer srv) {
        return new ApiCredentialDTO(WebProviderCode.TWELVE_DATA, "test-key", "", "http://127.0.0.1:" + srv.getAddress().getPort());
    }

    // --- parsePrices() : parsing pur, sans réseau -----------------------------------------

    @Test
    @DisplayName("parsePrices() mappe une réponse multi-symboles keyée par symbole")
    void parsePrices_mapsMultiSymbolResponse() {
        String body = """
                {
                  "EUR/USD": {"price": "1.1740"},
                  "USD/JPY": {"price": "149.50"}
                }
                """;

        Map<String, Double> result = TwelveDataQuoteClient.parsePrices(body, List.of("EUR/USD", "USD/JPY"));

        assertEquals(2, result.size());
        assertEquals(1.1740, result.get("EUR/USD"), 0.0001);
        assertEquals(149.50, result.get("USD/JPY"), 0.001);
    }

    @Test
    @DisplayName("parsePrices() mappe une réponse à un seul symbole, à plat")
    void parsePrices_mapsSingleSymbolFlatResponse() {
        String body = """
                {"price": "1.1740"}
                """;

        Map<String, Double> result = TwelveDataQuoteClient.parsePrices(body, List.of("EUR/USD"));

        assertEquals(1, result.size());
        assertEquals(1.1740, result.get("EUR/USD"), 0.0001);
    }

    @Test
    @DisplayName("parsePrices() ignore les entrées en erreur (pas de champ price) sans faire échouer le lot")
    void parsePrices_ignoresErrorEntries() {
        String body = """
                {
                  "EUR/USD": {"price": "1.1740"},
                  "USD/XXX": {"code": 400, "message": "symbol not found", "status": "error"}
                }
                """;

        Map<String, Double> result = TwelveDataQuoteClient.parsePrices(body, List.of("EUR/USD", "USD/XXX"));

        assertEquals(1, result.size());
        assertEquals(1.1740, result.get("EUR/USD"), 0.0001);
        assertNull(result.get("USD/XXX"));
    }

    @Test
    @DisplayName("parsePrices() retourne une map vide sur un corps invalide/absent")
    void parsePrices_returnsEmptyOnInvalidBody() {
        assertEquals(Map.of(), TwelveDataQuoteClient.parsePrices("not json", List.of("EUR/USD")));
        assertEquals(Map.of(), TwelveDataQuoteClient.parsePrices(null, List.of("EUR/USD")));
        assertEquals(Map.of(), TwelveDataQuoteClient.parsePrices("", List.of("EUR/USD")));
    }

    // --- parseQuotes() : parsing pur, sans réseau ------------------------------------------

    @Test
    @DisplayName("parseQuotes() mappe une réponse /quote avec timestamp et is_market_open")
    void parseQuotes_mapsResponseWithFreshnessFields() {
        String body = """
                {
                  "SPX": {"close": "5600.25", "timestamp": 1751500800, "is_market_open": true}
                }
                """;

        Map<String, TwelveDataQuote> result = TwelveDataQuoteClient.parseQuotes(body, List.of("SPX"));

        assertEquals(1, result.size());
        TwelveDataQuote quote = result.get("SPX");
        assertEquals(5600.25, quote.price(), 0.001);
        assertEquals(1751500800L, quote.timestampEpochSeconds());
        assertEquals(Boolean.TRUE, quote.marketOpen());
    }

    @Test
    @DisplayName("parseQuotes() tolère l'absence de timestamp/is_market_open (champs à null, pas d'entrée rejetée)")
    void parseQuotes_toleratesMissingFreshnessFields() {
        String body = """
                {"close": "5600.25"}
                """;

        Map<String, TwelveDataQuote> result = TwelveDataQuoteClient.parseQuotes(body, List.of("SPX"));

        assertEquals(1, result.size());
        TwelveDataQuote quote = result.get("SPX");
        assertEquals(5600.25, quote.price(), 0.001);
        assertNull(quote.timestampEpochSeconds());
        assertNull(quote.marketOpen());
    }

    // --- fetchPrices()/fetchQuotes() : bout en bout avec un vrai serveur HTTP local -------

    @Test
    @DisplayName("fetchPrices() mappe correctement une réponse 200")
    void fetchPrices_mapsValidResponse() throws IOException {
        server = startServer("/price", 200, """
                {"EUR/USD": {"price": "1.1740"}, "USD/JPY": {"price": "149.50"}}
                """);

        TwelveDataQuoteClient client = new TwelveDataQuoteClient();
        Map<String, Double> result = client.fetchPrices(credentialFor(server), List.of("EUR/USD", "USD/JPY"));

        assertEquals(1.1740, result.get("EUR/USD"), 0.0001);
        assertEquals(149.50, result.get("USD/JPY"), 0.001);
    }

    @Test
    @DisplayName("fetchPrices() retombe sur une map vide sur 500, sans exception")
    void fetchPrices_returnsEmpty_on5xx() throws IOException {
        server = startServer("/price", 500, "boom");

        TwelveDataQuoteClient client = new TwelveDataQuoteClient();
        Map<String, Double> result = client.fetchPrices(credentialFor(server), List.of("EUR/USD"));

        assertEquals(Map.of(), result);
    }

    @Test
    @DisplayName("fetchQuotes() retombe sur une map vide quand l'hôte est injoignable")
    void fetchQuotes_returnsEmpty_whenHostUnreachable() {
        TwelveDataQuoteClient client = new TwelveDataQuoteClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.TWELVE_DATA, "k", "", "http://127.0.0.1:1");

        Map<String, TwelveDataQuote> result = client.fetchQuotes(credential, List.of("SPX"));

        assertEquals(Map.of(), result);
    }

    @Test
    @DisplayName("fetchPrices()/fetchQuotes() retournent une map vide sur une liste de symboles vide/nulle, sans appel réseau")
    void fetch_returnsEmpty_onEmptySymbolList() {
        TwelveDataQuoteClient client = new TwelveDataQuoteClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.TWELVE_DATA, "k", "", "http://127.0.0.1:1");

        assertEquals(Map.of(), client.fetchPrices(credential, List.of()));
        assertEquals(Map.of(), client.fetchPrices(credential, null));
        assertEquals(Map.of(), client.fetchQuotes(credential, List.of()));
    }

    private HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        srv.start();
        return srv;
    }
}
