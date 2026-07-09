package fr.ses10doigts.tradeIO5.service.tree.macro.finnhub;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le format de réponse Finnhub exact (endpoint et noms de champs) n'a pas pu être vérifié contre
 * un appel réel (aucune clé disponible au moment de cette implémentation, cf. prompt
 * d'implémentation Lot 2, item G). Ces tests figent le comportement du parsing sur la forme
 * pressentie ({@code economicCalendar[].{event,country,time,impact,actual,estimate,prev}}) — voir
 * avertissement en tête de {@link FinnhubEconomicCalendarClient}. <b>À revalider dès qu'une clé
 * réelle est disponible.</b>
 */
@DisplayName("Macro - FinnhubEconomicCalendarClient")
class FinnhubEconomicCalendarClientTest {

    private static final String SAMPLE_RESPONSE = """
            {
              "economicCalendar": [
                {
                  "event": "FOMC Interest Rate Decision",
                  "country": "US",
                  "time": "2026-07-06T14:00:00Z",
                  "impact": "high",
                  "actual": "5.50%",
                  "estimate": "5.50%",
                  "prev": "5.25%"
                }
              ]
            }
            """;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("parseEvents() mappe la forme pressentie (wrapper economicCalendar)")
    void parseEvents_mapsPresumedShape() {
        List<MacroEvent> events = FinnhubEconomicCalendarClient.parseEvents(SAMPLE_RESPONSE);

        assertEquals(1, events.size());
        MacroEvent event = events.getFirst();
        assertEquals("FOMC Interest Rate Decision", event.getTitle());
        assertEquals("US", event.getCountry());
        assertEquals(MacroEventImpact.HIGH, event.getImpact());
        assertEquals(MacroEventSource.FINNHUB, event.getSource());
        assertEquals("5.50%", event.getActual());
        assertEquals("5.50%", event.getForecast());
        assertEquals("5.25%", event.getPrevious());
        assertEquals(Instant.parse("2026-07-06T14:00:00Z"), event.getDateTime());
    }

    @Test
    @DisplayName("parseEvents() tolère un tableau à la racine (sans wrapper economicCalendar)")
    void parseEvents_toleratesBareArray() {
        String body = """
                [
                  {"event": "CPI m/m", "country": "US", "time": "2026-07-06T12:30:00Z", "impact": "medium"}
                ]
                """;

        List<MacroEvent> events = FinnhubEconomicCalendarClient.parseEvents(body);

        assertEquals(1, events.size());
        assertEquals(MacroEventImpact.MEDIUM, events.getFirst().getImpact());
    }

    @Test
    @DisplayName("parseEvents() ignore les entrées incomplètes (event/time/impact manquant), sans exception")
    void parseEvents_ignoresIncompleteEntries() {
        String body = """
                {"economicCalendar": [{"country": "US"}]}
                """;

        List<MacroEvent> events = FinnhubEconomicCalendarClient.parseEvents(body);

        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("parseEvents() retourne une liste vide sur un corps invalide/absent")
    void parseEvents_returnsEmptyOnInvalidBody() {
        assertTrue(FinnhubEconomicCalendarClient.parseEvents("not json").isEmpty());
        assertTrue(FinnhubEconomicCalendarClient.parseEvents(null).isEmpty());
        assertTrue(FinnhubEconomicCalendarClient.parseEvents("").isEmpty());
    }

    @Test
    @DisplayName("fetchEvents() retombe sur une liste vide sur 500, sans exception")
    void fetchEvents_returnsEmpty_on5xx() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(FinnhubEconomicCalendarClient.PATH, exchange -> {
            byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FinnhubEconomicCalendarClient client = new FinnhubEconomicCalendarClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FINNHUB, "test-token", "", "http://127.0.0.1:" + server.getAddress().getPort());

        List<MacroEvent> events = client.fetchEvents(credential, Instant.EPOCH, Instant.now());

        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("fetchEvents() mappe correctement une réponse 200 de bout en bout")
    void fetchEvents_mapsValid200Response() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(FinnhubEconomicCalendarClient.PATH, exchange -> {
            byte[] body = SAMPLE_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        FinnhubEconomicCalendarClient client = new FinnhubEconomicCalendarClient();
        ApiCredentialDTO credential = new ApiCredentialDTO(
                WebProviderCode.FINNHUB, "test-token", "", "http://127.0.0.1:" + server.getAddress().getPort());

        List<MacroEvent> events = client.fetchEvents(credential, Instant.EPOCH, Instant.now());

        assertEquals(1, events.size());
        assertEquals("FOMC Interest Rate Decision", events.getFirst().getTitle());
    }
}
