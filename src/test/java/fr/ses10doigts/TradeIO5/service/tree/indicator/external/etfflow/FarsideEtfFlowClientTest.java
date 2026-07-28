package fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures dans {@code src/test/resources/fixtures/} : {@code farside-btc.html} reproduit le
 * contenu réel de <a href="https://farside.co.uk/btc/">...</a> tel que consulté au moment de la rédaction du prompt
 * d'implémentation Lot 3 (item I), avec la ligne du jour ("08 Jul 2026") volontairement à "-" sur
 * tous les émetteurs pour tester la règle "- != absence de donnée publiée, pas 0.0".
 */
@DisplayName("Indicator External - FarsideEtfFlowClient")
class FarsideEtfFlowClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String loadFixture(String name) {
        try (InputStream in = FarsideEtfFlowClientTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture introuvable : " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("parse() extrait la dernière ligne publiée (exclut la ligne du jour à '-')")
    void parse_fullFixture_extractsLatestPublishedRow() {
        EtfFlowResponse response = FarsideEtfFlowClient.parse(loadFixture("farside-btc.html"));

        assertTrue(response.isValid());
        assertEquals(LocalDate.of(2026, 7, 7), response.getDate());
        assertEquals(21.5, response.getTotal(), 0.001);

        assertEquals(54.8, response.getByIssuer().get("IBIT"), 0.001);
        assertEquals(-24.9, response.getByIssuer().get("FBTC"), 0.001);
        assertEquals(-8.4, response.getByIssuer().get("ARKB"), 0.001);
        // flux nul réel (0.0), doit être présent et valoir 0.0, pas absent.
        assertEquals(0.0, response.getByIssuer().get("BITB"), 0.001);
        assertEquals(0.0, response.getByIssuer().get("GBTC"), 0.001);

        // les 12 émetteurs étaient publiés le 07 Jul (aucun "-") : tous doivent être présents.
        assertEquals(12, response.getByIssuer().size());
    }

    @Test
    @DisplayName("parse() n'expose jamais la ligne du jour non publiée ('-' partout)")
    void parse_doesNotExposeUnpublishedTodayRow() {
        EtfFlowResponse response = FarsideEtfFlowClient.parse(loadFixture("farside-btc.html"));

        assertTrue(response.isValid());
        assertNotEquals(LocalDate.of(2026, 7, 8), response.getDate(), "La ligne '08 Jul 2026' (tout à '-') ne doit jamais être retenue comme dernière donnée publiée");
    }

    @Test
    @DisplayName("parse() ignore les lignes Fee/Total/Average/Maximum/Minimum (pas mappées comme donnée)")
    void parse_ignoresSummaryRows() {
        EtfFlowResponse response = FarsideEtfFlowClient.parse(loadFixture("farside-btc.html"));

        assertTrue(response.isValid());
        // Si la ligne "Total" (cumul depuis le lancement, valeurs à 5 chiffres) avait été
        // mal interprétée comme une ligne de donnée, IBIT vaudrait 60258 et non 54.8.
        assertEquals(54.8, response.getByIssuer().get("IBIT"), 0.001);
    }

    @Test
    @DisplayName("parse() retombe sur invalid() si l'en-tête (ligne des tickers) a disparu de la page")
    void parse_returnsInvalid_whenHeaderRowMissing() {
        EtfFlowResponse response = FarsideEtfFlowClient.parse(loadFixture("farside-btc-broken-no-header.html"));

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() si les colonnes émetteurs ont disparu de l'en-tête")
    void parse_returnsInvalid_whenIssuerColumnsMissing() {
        EtfFlowResponse response = FarsideEtfFlowClient.parse(loadFixture("farside-btc-broken-missing-column.html"));

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parse() retombe sur invalid() sur un HTML vide/sans tableau, sans exception")
    void parse_returnsInvalid_whenNoTable() {
        EtfFlowResponse response = FarsideEtfFlowClient.parse("<html><body><p>no table here</p></body></html>");

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("parseFlowNumber() gère la notation comptable, les virgules et '-' = absence")
    void parseFlowNumber_handlesAllFormats() {
        assertEquals(-172.0, FarsideEtfFlowClient.parseFlowNumber("(172.0)"), 0.001);
        assertEquals(0.0, FarsideEtfFlowClient.parseFlowNumber("0.0"), 0.001);
        assertEquals(60258.0, FarsideEtfFlowClient.parseFlowNumber("60,258"), 0.001);
        assertEquals(-27215.0, FarsideEtfFlowClient.parseFlowNumber("(27,215)"), 0.001);
        assertNull(FarsideEtfFlowClient.parseFlowNumber("-"));
        assertNull(FarsideEtfFlowClient.parseFlowNumber(""));
    }

    @Test
    @DisplayName("fetch() mappe correctement une réponse 200 valide de bout en bout")
    void fetch_mapsValid200Response() throws IOException {
        String html = loadFixture("farside-btc.html");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/btc/", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FarsideEtfFlowClient client = new FarsideEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FARSIDE, "", "", "http://127.0.0.1:" + server.getAddress().getPort());

        EtfFlowResponse response = client.fetch(credential, EtfFlowAsset.BTC);

        assertTrue(response.isValid());
        assertEquals(LocalDate.of(2026, 7, 7), response.getDate());
        assertEquals(21.5, response.getTotal(), 0.001);
    }

    @Test
    @DisplayName("fetch() retombe sur invalid() en cas de 500, sans exception")
    void fetch_returnsInvalid_on5xx() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/btc/", exchange -> {
            byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FarsideEtfFlowClient client = new FarsideEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FARSIDE, "", "", "http://127.0.0.1:" + server.getAddress().getPort());

        EtfFlowResponse response = client.fetch(credential, EtfFlowAsset.BTC);

        assertFalse(response.isValid());
    }

    @Test
    @DisplayName("fetch() retombe sur invalid() quand l'hôte est injoignable (pas d'exception)")
    void fetch_returnsInvalid_whenHostUnreachable() {
        FarsideEtfFlowClient client = new FarsideEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FARSIDE, "", "", "http://127.0.0.1:1");

        EtfFlowResponse response = client.fetch(credential, EtfFlowAsset.ETH);

        assertFalse(response.isValid());
    }

    // --- parseHistory()/fetchHistory() : addendum backfill Farside, 2026-07-17 ---
    // Même fixture que parse() (docs/etude-cache-etf-flow-historisation.md) : 12 lignes de donnée,
    // dont 1 non publiée ("08 Jul 2026", tout à "-") qui doit être exclue du résultat.

    @Test
    @DisplayName("parseHistory() extrait toutes les lignes publiées, exclut la ligne du jour non publiée")
    void parseHistory_fullFixture_extractsAllPublishedRows() {
        List<EtfFlowResponse> history = FarsideEtfFlowClient.parseHistory(loadFixture("farside-btc.html"));

        assertEquals(11, history.size(), "12 lignes de donnée dans la fixture, moins la ligne '08 Jul 2026' non publiée");
        assertTrue(history.stream().noneMatch(r -> r.getDate().equals(LocalDate.of(2026, 7, 8))));

        EtfFlowResponse first = history.get(0);
        assertEquals(LocalDate.of(2026, 6, 22), first.getDate());
        assertEquals(-68.3, first.getTotal(), 0.001);
        assertEquals(-172.0, first.getByIssuer().get("IBIT"), 0.001);

        EtfFlowResponse last = history.get(history.size() - 1);
        assertEquals(LocalDate.of(2026, 7, 7), last.getDate());
        assertEquals(21.5, last.getTotal(), 0.001);
    }

    @Test
    @DisplayName("parseHistory() ignore les lignes Fee/Total/Average/Maximum/Minimum")
    void parseHistory_ignoresSummaryRows() {
        List<EtfFlowResponse> history = FarsideEtfFlowClient.parseHistory(loadFixture("farside-btc.html"));

        // Si la ligne "Total" (cumul depuis le lancement) avait été mal interprétée comme une ligne
        // de donnée, elle apparaîtrait avec IBIT=60258 et une date invalide/absente.
        assertTrue(history.stream().allMatch(r -> Math.abs(r.getByIssuer().getOrDefault("IBIT", 0.0)) < 1000));
    }

    @Test
    @DisplayName("parseHistory() retombe sur liste vide si l'en-tête a disparu de la page")
    void parseHistory_returnsEmptyList_whenHeaderRowMissing() {
        List<EtfFlowResponse> history = FarsideEtfFlowClient.parseHistory(loadFixture("farside-btc-broken-no-header.html"));

        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("parseHistory() retombe sur liste vide sur un HTML vide/sans tableau, sans exception")
    void parseHistory_returnsEmptyList_whenNoTable() {
        List<EtfFlowResponse> history = FarsideEtfFlowClient.parseHistory("<html><body><p>no table here</p></body></html>");

        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("fetchHistory() tape la page 'all data' (pas la page live) et mappe toutes les lignes")
    void fetchHistory_mapsAllRows_fromAllDataPage() throws IOException {
        String html = loadFixture("farside-btc.html");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(EtfFlowAsset.BTC.getHistoryPath(), exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FarsideEtfFlowClient client = new FarsideEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FARSIDE, "", "", "http://127.0.0.1:" + server.getAddress().getPort());

        List<EtfFlowResponse> history = client.fetchHistory(credential, EtfFlowAsset.BTC);

        assertEquals(11, history.size());
    }

    @Test
    @DisplayName("fetchHistory() retombe sur liste vide quand l'hôte est injoignable (pas d'exception)")
    void fetchHistory_returnsEmptyList_whenHostUnreachable() {
        FarsideEtfFlowClient client = new FarsideEtfFlowClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FARSIDE, "", "", "http://127.0.0.1:1");

        List<EtfFlowResponse> history = client.fetchHistory(credential, EtfFlowAsset.BTC);

        assertTrue(history.isEmpty());
    }
}
