package fr.ses10doigts.tradeIO5.service.tree.macro.forexfactory;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Macro - ForexFactoryCalendarClient")
class ForexFactoryCalendarClientTest {

    // Fixture exacte du prompt d'implémentation Lot 2, item G (format confirmé en direct).
    private static final String SAMPLE_RESPONSE = """
            [
              {
                "title": "ISM Services PMI",
                "country": "USD",
                "date": "2026-07-06T10:00:00-04:00",
                "impact": "High",
                "forecast": "54.2",
                "previous": "54.5"
              }
            ]
            """;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("toMacroEvents() mappe correctement la fixture (impact, date avec offset, forecast/previous)")
    void toMacroEvents_mapsFixture() throws IOException {
        List<ForexFactoryEventRaw> raw = List.of(
                new ObjectMapper().readValue(SAMPLE_RESPONSE, ForexFactoryEventRaw[].class)
        );

        List<MacroEvent> events = ForexFactoryCalendarClient.toMacroEvents(raw);

        assertEquals(1, events.size());
        MacroEvent event = events.getFirst();
        assertEquals("ISM Services PMI", event.getTitle());
        assertEquals("USD", event.getCountry());
        assertEquals(MacroEventImpact.HIGH, event.getImpact());
        assertEquals(MacroEventSource.FOREXFACTORY, event.getSource());
        assertEquals("54.2", event.getForecast());
        assertEquals("54.5", event.getPrevious());
        assertEquals(OffsetDateTime.parse("2026-07-06T10:00:00-04:00").toInstant(), event.getDateTime());
    }

    @Test
    @DisplayName("toMacroEvents() ignore une entrée dont l'impact est inconnu, sans exception")
    void toMacroEvents_ignoresUnknownImpact() {
        ForexFactoryEventRaw raw = new ForexFactoryEventRaw();
        raw.setTitle("Something");
        raw.setCountry("USD");
        raw.setDate("2026-07-06T10:00:00-04:00");
        raw.setImpact("Unknown");

        List<MacroEvent> events = ForexFactoryCalendarClient.toMacroEvents(List.of(raw));

        assertEquals(0, events.size());
    }

    @Test
    @DisplayName("toMacroEvents() ignore une entrée dont la date est mal formée, sans exception")
    void toMacroEvents_ignoresMalformedDate() {
        ForexFactoryEventRaw raw = new ForexFactoryEventRaw();
        raw.setTitle("Something");
        raw.setCountry("USD");
        raw.setDate("not-a-date");
        raw.setImpact("High");

        List<MacroEvent> events = ForexFactoryCalendarClient.toMacroEvents(List.of(raw));

        assertEquals(0, events.size());
    }

    @Test
    @DisplayName("fetchEvents() filtre sur la fenêtre demandée")
    void fetchEvents_filtersOnRequestedWindow() throws IOException {
        server = startServer(200, SAMPLE_RESPONSE);

        ForexFactoryCalendarClient client = new ForexFactoryCalendarClient();
        ApiCredentialDTO credential = credentialFor(server);

        Instant eventTime = OffsetDateTime.parse("2026-07-06T10:00:00-04:00").toInstant();

        List<MacroEvent> inWindow = client.fetchEvents(credential, eventTime.minusSeconds(60), eventTime.plusSeconds(60));
        assertEquals(1, inWindow.size());

        List<MacroEvent> outOfWindow = client.fetchEvents(credential, eventTime.plusSeconds(3600), eventTime.plusSeconds(7200));
        assertEquals(0, outOfWindow.size());
    }

    @Test
    @DisplayName("fetchEvents() retombe sur une liste vide sur 500, sans exception")
    void fetchEvents_returnsEmpty_on5xx() throws IOException {
        server = startServer(500, "boom");

        ForexFactoryCalendarClient client = new ForexFactoryCalendarClient();
        List<MacroEvent> events = client.fetchEvents(credentialFor(server), Instant.EPOCH, Instant.now().plusSeconds(86400));

        assertTrue(events.isEmpty());
    }

    private ApiCredentialDTO credentialFor(HttpServer srv) {
        return new ApiCredentialDTO(WebProviderCode.FOREXFACTORY, "", "", "http://127.0.0.1:" + srv.getAddress().getPort());
    }

    private HttpServer startServer(int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext("/ff_calendar_thisweek.json", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        srv.start();
        return srv;
    }
}
