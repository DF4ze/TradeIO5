package fr.ses10doigts.tradeIO5.service.tree.macro;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.MacroCalendarException;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool MCP exposant {@link MacroEventCalendarService} : calendrier macro-économique agrégé
 * Finnhub + ForexFactory (dédoublonné). Service de lecture autonome, sans lien avec le triptyque
 * Indicator/Strategy/Opinion (pas de symbole, pas de {@code MarketDataset}) — d'où un tool dédié
 * plutôt qu'un détournement de {@code get_indicator}, cf. prompt d'implémentation "Connecter le
 * calendrier macro au MCP", "Pourquoi un nouveau tool dédié, pas get_indicator".
 * <p>
 * Même patron que {@link fr.ses10doigts.tradeIO5.service.dca.DcaMcpTools} : le tool renvoie une
 * {@code String} JSON sérialisée à la main (jamais une {@code Map} directement — cf. javadoc
 * {@code DcaMcpTools} pour la raison, un bug de spring-ai-starter-mcp-server-webmvc 1.0.9 sur le
 * remplissage de {@code content}), et n'oublie jamais de capturer les exceptions.
 * <p>
 * {@code toJson}/{@code toJsonOrError} sont dupliquées à l'identique depuis {@code DcaMcpTools} —
 * même choix assumé ailleurs dans le projet (cf. {@code TreeAnalysisMcpTools}/{@code DcaMcpTools})
 * plutôt que d'extraire une classe utilitaire partagée pour 2 méthodes.
 */
@Component
public class MacroCalendarMcpTools {

    private static final Logger logger = LoggerFactory.getLogger(MacroCalendarMcpTools.class);

    private final MacroEventCalendarService macroEventCalendarService;
    private final DomainClock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MacroCalendarMcpTools(MacroEventCalendarService macroEventCalendarService, DomainClock clock) {
        this.macroEventCalendarService = macroEventCalendarService;
        this.clock = clock;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize MCP tool result", e);
        }
    }

    /** Cf. DcaMcpTools#toJsonOrError : jamais laisser fuiter une exception hors d'un @Tool. */
    private String toJsonOrError(String toolName, java.util.function.Supplier<Map<String, Object>> supplier) {
        try {
            return toJson(supplier.get());
        } catch (Exception e) {
            logger.error("❌ Tool MCP '{}' a échoué", toolName, e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", true);
            error.put("tool", toolName);
            error.put("exception", e.getClass().getName());
            error.put("message", e.getMessage());
            List<String> frames = new ArrayList<>();
            for (StackTraceElement el : e.getStackTrace()) {
                frames.add(el.toString());
                if (frames.size() >= 15) break;
            }
            error.put("stackTop", frames);
            if (e.getCause() != null) {
                error.put("causeType", e.getCause().getClass().getName());
                error.put("causeMessage", e.getCause().getMessage());
            }
            return toJson(error);
        }
    }

    @Tool(
            name = "get_macro_calendar",
            description = "Liste les événements macro-économiques (FOMC, NFP, CPI, etc.) prévus dans une "
                    + "fenêtre de dates, agrégés depuis Finnhub et ForexFactory (dédoublonnés). Impact "
                    + "HIGH/MEDIUM/LOW/HOLIDAY par événement. Utile pour anticiper une fenêtre de risque "
                    + "événementiel avant de prendre une décision de trading."
    )
    public String getMacroCalendar(
            @ToolParam(description = "Date de début (incluse), format ISO yyyy-MM-dd, interprétée en début de journée UTC") String fromDate,
            @ToolParam(description = "Date de fin (incluse), format ISO yyyy-MM-dd, interprétée en fin de journée UTC") String toDate,
            @ToolParam(description = "Filtre : n'inclure que les événements d'impact >= ce seuil (LOW/MEDIUM/HIGH). "
                    + "Omis = tous les événements y compris HOLIDAY.", required = false) MacroEventImpact minImpact
    ) {
        return toJsonOrError("get_macro_calendar", () -> {
            LocalDate from = parseDate(fromDate, "fromDate");
            LocalDate to = parseDate(toDate, "toDate");
            Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1);

            List<MacroEvent> events = macroEventCalendarService.getEvents(fromInstant, toInstant);
            if (minImpact != null) {
                events = events.stream()
                        .filter(e -> MacroEventCalendarService.impactRank(e.getImpact())
                                >= MacroEventCalendarService.impactRank(minImpact))
                        .toList();
            }
            return calendarResponse(fromDate, toDate, minImpact, events);
        });
    }

    @Tool(
            name = "check_macro_risk_window",
            description = "Indique si un événement macro d'impact >= minImpact tombe dans une fenêtre de "
                    + "N heures autour de maintenant (now-window, now+window). Utile pour éviter de décider "
                    + "juste avant/après une annonce à fort impact (FOMC, NFP, CPI)."
    )
    public String checkMacroRiskWindow(
            @ToolParam(description = "Demi-largeur de la fenêtre en heures autour de maintenant") double windowHours,
            @ToolParam(description = "Impact minimal déclenchant la fenêtre de risque : LOW, MEDIUM ou HIGH") MacroEventImpact minImpact
    ) {
        return toJsonOrError("check_macro_risk_window", () -> {
            Duration window = Duration.ofMinutes(Math.round(windowHours * 60));
            Instant now = clock.now();
            boolean within = macroEventCalendarService.isWithinRiskWindow(now, window, minImpact);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("withinRiskWindow", within);
            response.put("windowHours", windowHours);
            response.put("minImpact", minImpact != null ? minImpact.name() : null);
            return response;
        });
    }

    private static LocalDate parseDate(String value, String fieldName) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new MacroCalendarException(fieldName + " invalide, format attendu yyyy-MM-dd : " + value, e);
        }
    }

    private static Map<String, Object> calendarResponse(
            String fromDate, String toDate, MacroEventImpact minImpact, List<MacroEvent> events) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fromDate", fromDate);
        response.put("toDate", toDate);
        response.put("minImpact", minImpact != null ? minImpact.name() : null);
        response.put("eventCount", events.size());
        response.put("events", eventsJson(events));
        return response;
    }

    private static List<Map<String, Object>> eventsJson(List<MacroEvent> events) {
        List<Map<String, Object>> list = new ArrayList<>(events.size());
        for (MacroEvent event : events) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", event.getTitle());
            map.put("country", event.getCountry());
            map.put("dateTime", event.getDateTime() != null ? event.getDateTime().toString() : null);
            map.put("impact", event.getImpact() != null ? event.getImpact().name() : null);
            map.put("source", event.getSource() != null ? event.getSource().name() : null);
            map.put("forecast", event.getForecast());
            map.put("previous", event.getPrevious());
            map.put("actual", event.getActual());
            list.add(map);
        }
        return list;
    }
}
