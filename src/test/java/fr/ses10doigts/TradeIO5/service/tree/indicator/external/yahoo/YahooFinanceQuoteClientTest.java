package fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrairement à {@code TwelveDataQuoteClientTest} (format jamais vérifié contre un appel réel au
 * moment de son écriture), le format de réponse Yahoo Finance ci-dessous a été confirmé par appel
 * réel le 2026-07-15 (cf. javadoc {@link YahooFinanceQuoteClient}) — ces tests figent un
 * comportement déjà validé, pas une hypothèse à revalider plus tard.
 */
@DisplayName("Indicator External - YahooFinanceQuoteClient")
class YahooFinanceQuoteClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ApiCredentialDTO credentialFor(HttpServer srv) {
        return new ApiCredentialDTO(WebProviderCode.YAHOO_FINANCE, "", "", "http://127.0.0.1:" + srv.getAddress().getPort());
    }

    // --- parseQuote() : parsing pur, sans réseau -------------------------------------------

    @Test
    @DisplayName("parseQuote() mappe une réponse valide (meta.regularMarketPrice + regularMarketTime)")
    void parseQuote_mapsValidResponse() {
        String body = """
                {"chart":{"result":[{"meta":{"symbol":"^GSPC","regularMarketPrice":7543.59,"regularMarketTime":1784062596}}],"error":null}}
                """;

        YahooFinanceQuote quote = YahooFinanceQuoteClient.parseQuote(body);

        assertNotNull(quote);
        assertEquals(7543.59, quote.price(), 0.001);
        assertEquals(1784062596L, quote.timestampEpochSeconds());
    }

    @Test
    @DisplayName("parseQuote() tolère l'absence de regularMarketTime (timestamp à null, pas d'exception)")
    void parseQuote_toleratesMissingTimestamp() {
        String body = """
                {"chart":{"result":[{"meta":{"symbol":"^GSPC","regularMarketPrice":7543.59}}],"error":null}}
                """;

        YahooFinanceQuote quote = YahooFinanceQuoteClient.parseQuote(body);

        assertNotNull(quote);
        assertEquals(7543.59, quote.price(), 0.001);
        assertNull(quote.timestampEpochSeconds());
    }

    @Test
    @DisplayName("parseQuote() retourne null quand result est null (symbole inconnu, forme réelle vérifiée)")
    void parseQuote_returnsNull_whenResultIsNull() {
        String body = """
                {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}
                """;

        assertNull(YahooFinanceQuoteClient.parseQuote(body));
    }

    @Test
    @DisplayName("parseQuote() retourne null quand regularMarketPrice est absent")
    void parseQuote_returnsNull_whenPriceMissing() {
        String body = """
                {"chart":{"result":[{"meta":{"symbol":"^GSPC"}}],"error":null}}
                """;

        assertNull(YahooFinanceQuoteClient.parseQuote(body));
    }

    @Test
    @DisplayName("parseQuote() retourne null sur un corps invalide/absent")
    void parseQuote_returnsNull_onInvalidBody() {
        assertNull(YahooFinanceQuoteClient.parseQuote("not json"));
        assertNull(YahooFinanceQuoteClient.parseQuote(null));
        assertNull(YahooFinanceQuoteClient.parseQuote(""));
    }

    // --- fetchQuotes() : bout en bout avec un vrai serveur HTTP local ----------------------

    @Test
    @DisplayName("fetchQuotes() mappe correctement une réponse 200 et envoie un User-Agent (obligatoire côté Yahoo)")
    void fetchQuotes_mapsValidResponse_andSendsUserAgent() throws IOException {
        AtomicReference<String> receivedUserAgent = new AtomicReference<>();
        // Contexte enregistré avec le chemin décodé : com.sun.net.httpserver matche sur
        // HttpExchange.getRequestURI().getPath() (java.net.URI décode %5E -> ^ pour getPath()),
        // même si le client envoie bien "%5EGSPC" sur le fil (uriBuilder encode "^" via le
        // template path variable).
        server = startServer("/v8/finance/chart/^GSPC", 200, """
                {"chart":{"result":[{"meta":{"regularMarketPrice":7543.59,"regularMarketTime":1784062596}}],"error":null}}
                """, receivedUserAgent);

        YahooFinanceQuoteClient client = new YahooFinanceQuoteClient();
        Map<String, YahooFinanceQuote> result = client.fetchQuotes(credentialFor(server), List.of("^GSPC"));

        assertEquals(7543.59, result.get("^GSPC").price(), 0.001);
        assertNotNull(receivedUserAgent.get());
        assertTrue(receivedUserAgent.get().contains("Mozilla"));
    }

    @Test
    @DisplayName("fetchQuotes() retombe sur une map vide sur 500, sans exception")
    void fetchQuotes_returnsEmpty_on5xx() throws IOException {
        server = startServer("/v8/finance/chart/^GSPC", 500, "boom", new AtomicReference<>());

        YahooFinanceQuoteClient client = new YahooFinanceQuoteClient();
        Map<String, YahooFinanceQuote> result = client.fetchQuotes(credentialFor(server), List.of("^GSPC"));

        assertEquals(Map.of(), result);
    }

    @Test
    @DisplayName("fetchQuotes() retombe sur une map vide quand l'hôte est injoignable")
    void fetchQuotes_returnsEmpty_whenHostUnreachable() {
        YahooFinanceQuoteClient client = new YahooFinanceQuoteClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.YAHOO_FINANCE, "", "", "http://127.0.0.1:1");

        Map<String, YahooFinanceQuote> result = client.fetchQuotes(credential, List.of("^GSPC"));

        assertEquals(Map.of(), result);
    }

    @Test
    @DisplayName("fetchQuotes() retourne une map vide sur une liste de symboles vide/nulle, sans appel réseau")
    void fetchQuotes_returnsEmpty_onEmptySymbolList() {
        YahooFinanceQuoteClient client = new YahooFinanceQuoteClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.YAHOO_FINANCE, "", "", "http://127.0.0.1:1");

        assertEquals(Map.of(), client.fetchQuotes(credential, List.of()));
        assertEquals(Map.of(), client.fetchQuotes(credential, null));
    }

    private HttpServer startServer(String path, int status, String body, AtomicReference<String> receivedUserAgent) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext(path, exchange -> {
            receivedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        srv.start();
        return srv;
    }
}
