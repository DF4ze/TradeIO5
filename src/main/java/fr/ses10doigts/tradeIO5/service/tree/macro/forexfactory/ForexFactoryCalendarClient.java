package fr.ses10doigts.tradeIO5.service.tree.macro.forexfactory;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventSource;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client ForexFactory (étude "indicateurs-macro-externes" §14 item G) :
 * {@code GET /ff_calendar_thisweek.json}, aucune authentification, format confirmé en direct (voir
 * {@link ForexFactoryEventRaw}).
 * <p>
 * <b>Throttle</b> : l'endpoint tolère au maximum 2 téléchargements/5 minutes côté serveur. Cet
 * appelant ne connaît pas la fréquence d'appel de {@code MacroEventCalendarService}, donc plutôt
 * que de faire confiance à l'appelant pour respecter cette limite, un cache mémoire interne
 * (fenêtre {@link #THROTTLE_WINDOW}, largement sous la limite serveur) sert la dernière réponse
 * connue en cas d'appel rapproché — protège aussi contre un bug d'appel en boucle côté appelant.
 * <p>
 * <b>Limite connue</b> : cet endpoint ne renvoie que les événements de la semaine en cours,
 * indépendamment de {@code from}/{@code to} — {@link #fetchEvents} filtre côté client sur la
 * fenêtre demandée après coup, mais ne peut pas remonter plus loin que "cette semaine" (documenté
 * ici et dans {@link fr.ses10doigts.tradeIO5.service.tree.macro.MacroEventProvider}).
 */
@Component
public class ForexFactoryCalendarClient extends AbstractExternalIndicator implements MacroEventProvider {

    private static final String PATH = "/ff_calendar_thisweek.json";
    private static final Duration THROTTLE_WINDOW = Duration.ofMinutes(2);

    private final Logger logger = LoggerFactory.getLogger(ForexFactoryCalendarClient.class);

    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    @Override
    public List<MacroEvent> fetchEvents(ApiCredentialDTO credential, Instant from, Instant to) {
        List<MacroEvent> allEvents = fetchThisWeek(credential);
        return allEvents.stream()
                .filter(e -> e.getDateTime() != null && !e.getDateTime().isBefore(from) && !e.getDateTime().isAfter(to))
                .toList();
    }

    private List<MacroEvent> fetchThisWeek(ApiCredentialDTO credential) {
        CacheEntry cached = cache.get();
        Instant now = Instant.now();
        if (cached != null && Duration.between(cached.fetchedAt, now).compareTo(THROTTLE_WINDOW) < 0) {
            return cached.events;
        }

        try {
            List<ForexFactoryEventRaw> raw = getWebClient(credential).get()
                    .uri(PATH)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(new ParameterizedTypeReference<List<ForexFactoryEventRaw>>() {})
                    .timeout(Duration.ofSeconds(20))
                    .block();

            List<MacroEvent> events = toMacroEvents(raw);
            cache.set(new CacheEntry(events, now));
            return events;
        } catch (ExternalApiException e) {
            logger.warn("ForexFactory unavailable: {}", e.getMessage());
            return cached != null ? cached.events : List.of();
        } catch (Exception e) {
            logger.error("ForexFactory unexpected error", e);
            return cached != null ? cached.events : List.of();
        }
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire (patron
     * {@code DefiLlamaStablecoinClient.aggregate}).
     */
    static List<MacroEvent> toMacroEvents(List<ForexFactoryEventRaw> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(ForexFactoryCalendarClient::toMacroEvent)
                .filter(Objects::nonNull)
                .toList();
    }

    private static MacroEvent toMacroEvent(ForexFactoryEventRaw raw) {
        if (raw == null || raw.getDate() == null || raw.getImpact() == null) {
            return null;
        }

        Instant dateTime;
        try {
            dateTime = OffsetDateTime.parse(raw.getDate()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }

        MacroEventImpact impact = parseImpact(raw.getImpact());
        if (impact == null) {
            return null;
        }

        return MacroEvent.builder()
                .title(raw.getTitle())
                .country(raw.getCountry())
                .dateTime(dateTime)
                .impact(impact)
                .source(MacroEventSource.FOREXFACTORY)
                .forecast(raw.getForecast())
                .previous(raw.getPrevious())
                .build();
    }

    private static MacroEventImpact parseImpact(String impact) {
        try {
            return MacroEventImpact.valueOf(impact.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("ForexFactory 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("ForexFactory 5xx: " + body));
    }

    private record CacheEntry(List<MacroEvent> events, Instant fetchedAt) {
    }
}
