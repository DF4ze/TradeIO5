package fr.ses10doigts.tradeIO5.service.tree.macro.finnhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventSource;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client Finnhub (étude "indicateurs-macro-externes" §14 item G) : base
 * {@code https://finnhub.io/api/v1}, endpoint <b>pressenti</b> {@value #PATH} (à confirmer à
 * l'implémentation — la doc publique n'a pas pu être récupérée en contenu texte lors de l'étude,
 * cf. prompt d'implémentation ; ouvrir {@code https://finnhub.io/docs/api/economic-calendar}
 * directement dans un navigateur avant de figer ce mapping), auth {@code token} en query param.
 * <p>
 * <b>Format de réponse non vérifié contre un appel réel</b> (aucune clé disponible au moment de
 * cette implémentation) : le parsing ci-dessous est écrit défensivement via {@link JsonNode} sur
 * la base des noms de champs les plus probables pour ce type d'API
 * ({@code economicCalendar}/{@code event}/{@code country}/{@code time}/{@code impact}/
 * {@code actual}/{@code estimate}/{@code prev}) — une entrée dont la forme ne correspond pas est
 * simplement ignorée (jamais d'exception). <b>À revalider dès la création d'une vraie clé.</b>
 */
@Component
public class FinnhubEconomicCalendarClient extends AbstractExternalIndicator implements MacroEventProvider {

    static final String PATH = "/calendar/economic";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_PARAM_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Logger logger = LoggerFactory.getLogger(FinnhubEconomicCalendarClient.class);

    @Override
    public List<MacroEvent> fetchEvents(ApiCredentialDTO credential, Instant from, Instant to) {
        try {
            String body = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path(PATH)
                            .queryParam("from", DATE_PARAM_FORMAT.format(from.atOffset(ZoneOffset.UTC)))
                            .queryParam("to", DATE_PARAM_FORMAT.format(to.atOffset(ZoneOffset.UTC)))
                            .queryParam("token", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            return parseEvents(body);
        } catch (ExternalApiException e) {
            logger.warn("Finnhub (economic calendar) unavailable: {}", e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.error("Finnhub (economic calendar) unexpected error", e);
            return List.of();
        }
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire sans clé réelle (patron
     * {@code DefiLlamaStablecoinClient.aggregate}). Tolère l'absence du wrapper
     * {@code economicCalendar} (utilise directement la racine si c'est un tableau).
     */
    static List<MacroEvent> parseEvents(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            return List.of();
        }

        JsonNode array = root.has("economicCalendar") ? root.get("economicCalendar") : root;
        if (array == null || !array.isArray()) {
            return List.of();
        }

        List<MacroEvent> events = new ArrayList<>();
        Iterator<JsonNode> it = array.elements();
        while (it.hasNext()) {
            MacroEvent event = toMacroEvent(it.next());
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    private static MacroEvent toMacroEvent(JsonNode node) {
        if (node == null) {
            return null;
        }

        String title = textOrNull(node, "event");
        String country = textOrNull(node, "country");
        String timeText = textOrNull(node, "time");
        String impactText = textOrNull(node, "impact");

        if (title == null || timeText == null || impactText == null) {
            return null;
        }

        Instant dateTime = parseFinnhubTime(timeText);
        if (dateTime == null) {
            return null;
        }

        MacroEventImpact impact = parseImpact(impactText);
        if (impact == null) {
            return null;
        }

        return MacroEvent.builder()
                .title(title)
                .country(country)
                .dateTime(dateTime)
                .impact(impact)
                .source(MacroEventSource.FINNHUB)
                .forecast(textOrNull(node, "estimate"))
                .previous(textOrNull(node, "prev"))
                .actual(textOrNull(node, "actual"))
                .build();
    }

    private static Instant parseFinnhubTime(String timeText) {
        // Champ "time" pressenti comme une chaîne ISO-8601 (avec ou sans offset) — non vérifié,
        // cf. avertissement de classe. OffsetDateTime en priorité (offset explicite), repli sur
        // Instant.parse (suffixe "Z"/UTC) si le premier échoue.
        try {
            return OffsetDateTime.parse(timeText).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(timeText);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static MacroEventImpact parseImpact(String impact) {
        try {
            return MacroEventImpact.valueOf(impact.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Finnhub 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Finnhub 5xx: " + body));
    }
}
