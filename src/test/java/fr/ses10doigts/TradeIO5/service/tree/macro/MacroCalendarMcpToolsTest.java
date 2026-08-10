package fr.ses10doigts.tradeIO5.service.tree.macro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventSource;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cf. {@code DcaMcpToolsTest} pour le patron : service mocké, assertions sur le JSON renvoyé (le
 * tool sérialise lui-même, jamais de retour {@code Map} direct — cf. javadoc
 * {@link MacroCalendarMcpTools}).
 */
@DisplayName("MacroCalendarMcpTools — get_macro_calendar / check_macro_risk_window")
@ExtendWith(MockitoExtension.class)
class MacroCalendarMcpToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private MacroEventCalendarService macroEventCalendarService;

    @Mock
    private DomainClock clock;

    private MacroCalendarMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new MacroCalendarMcpTools(macroEventCalendarService, clock);
    }

    private static MacroEvent event(String title, MacroEventImpact impact, Instant dateTime) {
        return MacroEvent.builder()
                .title(title)
                .country("US")
                .dateTime(dateTime)
                .impact(impact)
                .source(MacroEventSource.FINNHUB)
                .forecast("1.0%")
                .previous("0.9%")
                .actual(null)
                .build();
    }

    @Test
    @DisplayName("fromDate/toDate valides, minImpact omis → tous les événements du mock apparaissent, dans l'ordre")
    void getMacroCalendar_noImpactFilter_returnsAllEventsInOrder() throws Exception {
        MacroEvent fomc = event("FOMC Interest Rate Decision", MacroEventImpact.HIGH, Instant.parse("2026-08-15T14:00:00Z"));
        MacroEvent nfp = event("Non-Farm Payrolls", MacroEventImpact.HIGH, Instant.parse("2026-08-20T12:30:00Z"));
        when(macroEventCalendarService.getEvents(any(), any())).thenReturn(List.of(fomc, nfp));

        String json = tools.getMacroCalendar("2026-08-01", "2026-08-31", null);

        JsonNode node = MAPPER.readTree(json);
        assertFalse(node.has("error"));
        assertEquals(2, node.get("eventCount").asInt());
        assertEquals("FOMC Interest Rate Decision", node.get("events").get(0).get("title").asText());
        assertEquals("Non-Farm Payrolls", node.get("events").get(1).get("title").asText());
    }

    @Test
    @DisplayName("minImpact=MEDIUM → un événement LOW est filtré, un événement HIGH reste")
    void getMacroCalendar_minImpactFilter_excludesLowerImpactEvents() throws Exception {
        MacroEvent low = event("Minor release", MacroEventImpact.LOW, Instant.parse("2026-08-10T09:00:00Z"));
        MacroEvent high = event("FOMC Interest Rate Decision", MacroEventImpact.HIGH, Instant.parse("2026-08-15T14:00:00Z"));
        when(macroEventCalendarService.getEvents(any(), any())).thenReturn(List.of(low, high));

        String json = tools.getMacroCalendar("2026-08-01", "2026-08-31", MacroEventImpact.MEDIUM);

        JsonNode node = MAPPER.readTree(json);
        assertEquals(1, node.get("eventCount").asInt());
        assertEquals("FOMC Interest Rate Decision", node.get("events").get(0).get("title").asText());
    }

    @Test
    @DisplayName("toDate inclusif : la borne 'to' passée au service couvre jusqu'à la fin du dernier jour (pas le début)")
    void getMacroCalendar_toDateIsInclusiveOfEntireLastDay() {
        when(macroEventCalendarService.getEvents(any(), any())).thenReturn(List.of());

        tools.getMacroCalendar("2026-08-01", "2026-08-31", null);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(macroEventCalendarService).getEvents(fromCaptor.capture(), toCaptor.capture());

        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), fromCaptor.getValue());
        // Fin de journée UTC inclusive, pas début de journée : un événement le 31/08 à 23h doit
        // tomber avant cette borne.
        Instant to = toCaptor.getValue();
        assertTrue(to.isAfter(Instant.parse("2026-08-31T23:00:00Z")));
        assertEquals(Instant.parse("2026-09-01T00:00:00Z").minusMillis(1), to);
    }

    @Test
    @DisplayName("fromDate invalide (format non yyyy-MM-dd) → JSON error:true, pas d'exception qui remonte")
    void getMacroCalendar_invalidFromDate_returnsJsonError() throws Exception {
        String json = tools.getMacroCalendar("15-08-2026", "2026-08-31", null);

        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.get("error").asBoolean());
        assertTrue(node.get("message").asText().contains("fromDate"));
    }

    @Test
    @DisplayName("toDate invalide (format non yyyy-MM-dd) → JSON error:true, pas d'exception qui remonte")
    void getMacroCalendar_invalidToDate_returnsJsonError() throws Exception {
        String json = tools.getMacroCalendar("2026-08-01", "not-a-date", null);

        JsonNode node = MAPPER.readTree(json);
        assertTrue(node.get("error").asBoolean());
        assertTrue(node.get("message").asText().contains("toDate"));
    }

    @Test
    @DisplayName("Aucune credential résolue (le service renvoie une liste vide) → JSON avec tableau vide, pas d'erreur")
    void getMacroCalendar_noEventsFromService_returnsEmptyArrayWithoutError() throws Exception {
        when(macroEventCalendarService.getEvents(any(), any())).thenReturn(List.of());

        String json = tools.getMacroCalendar("2026-08-01", "2026-08-31", null);

        JsonNode node = MAPPER.readTree(json);
        assertFalse(node.has("error"));
        assertEquals(0, node.get("eventCount").asInt());
        assertTrue(node.get("events").isEmpty());
    }

    @Test
    @DisplayName("check_macro_risk_window : withinRiskWindow=true reflété dans le JSON, fenêtre fractionnaire convertie en Duration précis")
    void checkMacroRiskWindow_withinWindowTrue_convertsFractionalHoursToDuration() throws Exception {
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        when(clock.now()).thenReturn(now);
        when(macroEventCalendarService.isWithinRiskWindow(eq(now), any(), eq(MacroEventImpact.HIGH)))
                .thenReturn(true);

        String json = tools.checkMacroRiskWindow(1.5, MacroEventImpact.HIGH);

        JsonNode node = MAPPER.readTree(json);
        assertFalse(node.has("error"));
        assertTrue(node.get("withinRiskWindow").asBoolean());
        assertEquals("HIGH", node.get("minImpact").asText());

        ArgumentCaptor<Duration> windowCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(macroEventCalendarService).isWithinRiskWindow(eq(now), windowCaptor.capture(), eq(MacroEventImpact.HIGH));
        assertEquals(Duration.ofMinutes(90), windowCaptor.getValue());
    }

    @Test
    @DisplayName("check_macro_risk_window : withinRiskWindow=false reflété dans le JSON")
    void checkMacroRiskWindow_withinWindowFalse_reflectedInJson() throws Exception {
        Instant now = Instant.parse("2026-08-10T12:00:00Z");
        when(clock.now()).thenReturn(now);
        when(macroEventCalendarService.isWithinRiskWindow(eq(now), any(), eq(MacroEventImpact.LOW)))
                .thenReturn(false);

        String json = tools.checkMacroRiskWindow(2.0, MacroEventImpact.LOW);

        JsonNode node = MAPPER.readTree(json);
        assertFalse(node.get("withinRiskWindow").asBoolean());
    }
}
