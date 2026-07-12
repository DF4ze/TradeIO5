package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import reactor.core.publisher.Mono;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Client transcript générique pour une vidéo YouTube (docs/etude-veille-media-youtube.md §4, Lot 1).
 * <p>
 * <b>Historique</b> : une 1ʳᵉ version scrapait {@code var ytInitialPlayerResponse = {...};} depuis
 * le HTML de {@code /watch} (comptage d'accolades). Invalidé par un test manuel réseau réel le
 * 2026-07-12 (cf. {@code YoutubeManualNetworkTest}) : les {@code captionTracks[].baseUrl} embarqués
 * dans le HTML servi à un client "anonyme" sont dégradés côté YouTube ({@code ip=0.0.0.0} au lieu
 * d'une vraie IP dans l'URL signée) — la requête vers cette URL renvoie {@code 200 OK} avec un
 * corps vide, quels que soient User-Agent/Referer/cookies. Confirmé en lisant le code source de
 * {@code youtube-transcript-api} (celle utilisée par {@code tools/media_watch/probe_transcript.py}) :
 * cette lib ne scrape <b>pas</b> {@code ytInitialPlayerResponse} — elle appelle l'API interne
 * Innertube en POST, qui renvoie des {@code baseUrl} correctement signés. Reproduit avec succès sur
 * la même vidéo au même moment (526 segments FR récupérés côté Python), confirmant que le problème
 * est structurel (mécanisme de scrape HTML) et non un blocage général/PoToken.
 * <p>
 * <b>Mécanisme actuel</b> (aligné sur {@code youtube-transcript-api}) :
 * <ol>
 *   <li>{@code GET /watch?v={videoId}} → HTML brut, utilisé pour en extraire
 *       {@code INNERTUBE_API_KEY} par regex (valeur statique par page). Les en-têtes
 *       {@code Set-Cookie} de cette réponse (session/visiteur YouTube) sont capturés.</li>
 *   <li>{@code POST /youtubei/v1/player?key={apiKey}} avec un corps JSON
 *       {@code {"context": {"client": {"clientName": "ANDROID", "clientVersion": "..."}},
 *       "videoId": "..."}} — contexte client "ANDROID" (même choix que {@code youtube-transcript-api}),
 *       <b>en propageant les cookies capturés à l'étape précédente</b> via l'en-tête {@code Cookie}.
 *       Point critique validé empiriquement le 2026-07-12 : sans ces cookies, l'API Innertube renvoie
 *       parfois un {@code captionTracks[].baseUrl} dégradé (contenant {@code &exp=xpe}, cf.
 *       {@code youtube_transcript_api._errors.PoTokenRequired}) qui renvoie ensuite un corps vide ;
 *       avec les cookies de la même "session" (même si anonyme, non authentifiée), l'API renvoie un
 *       {@code baseUrl} fonctionnel (avec {@code &fmt=srv3}, sans {@code exp=xpe}) de façon
 *       reproductible (5/5 tentatives réussies avec cookies vs. 5/5 échecs sans, sur la même vidéo).</li>
 *   <li>Réponse JSON complète (pas besoin d'extraction par comptage d'accolades cette fois : c'est
 *       un document JSON autonome) → {@code captions.playerCaptionsTracklistRenderer.captionTracks[]},
 *       piste {@code fr} en priorité, sinon la première disponible.</li>
 *   <li>{@code baseUrl} est une URL absolue (domaine {@code youtube.com}/{@code googlevideo.com}) :
 *       appelée via {@code .uri(URI.create(baseUrl))} sur le même {@code WebClient} (accepte une URI
 *       absolue malgré le {@code baseUrl} configuré, cf. {@code AbstractExternalIndicator}), avec les
 *       mêmes cookies propagés par cohérence (pas strictement nécessaire d'après les tests, mais
 *       sans risque et plus proche du comportement d'une vraie session navigateur). Le paramètre
 *       {@code fmt} (typiquement {@code fmt=srv3}) est retiré au préalable par
 *       {@link #stripFmtParam} : avec {@code fmt=srv3}, YouTube renvoie un schéma XML différent
 *       ({@code <p t= d=><s>...}) que le parseur ci-dessous ne lit pas (0 segment sur une réponse
 *       de 136 508 caractères pourtant valide) ; sans {@code fmt}, le format simple attendu est
 *       renvoyé (526 segments obtenus sur la vidéo de test, identique au nombre côté
 *       {@code youtube-transcript-api} Python).</li>
 *   <li>Réponse XML {@code <transcript><text start dur>...</text></transcript>}, dé-échappée via
 *       {@code org.jsoup.parser.Parser.unescapeEntities} (jsoup déjà une dépendance du projet).</li>
 * </ol>
 * <b>Fragilité assumée</b> : dépend d'une API interne non documentée de YouTube (Innertube) et d'un
 * comportement de cookies de session non documenté, qui peuvent changer sans préavis — même limite
 * que la lib Python de référence. Parsing défensif — tout champ absent ou toute anomalie →
 * {@link Optional#empty()}, jamais d'exception qui remonte jusqu'au job planifié.
 */
@Component
public class YoutubeTranscriptClient extends AbstractExternalIndicator {

    private static final String WATCH_PATH = "/watch";
    private static final String INNERTUBE_PLAYER_PATH = "/youtubei/v1/player";
    private static final String PREFERRED_LANGUAGE = "fr";

    /** Même contexte client que {@code youtube-transcript-api} (INNERTUBE_CONTEXT, _settings.py). */
    private static final String INNERTUBE_CLIENT_NAME = "ANDROID";
    private static final String INNERTUBE_CLIENT_VERSION = "20.10.38";

    private static final Pattern API_KEY_PATTERN = Pattern.compile("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([a-zA-Z0-9_-]+)\"");

    private final Logger logger = LoggerFactory.getLogger(YoutubeTranscriptClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<List<TranscriptSegment>> fetchTranscript(ApiCredentialDTO credential, String videoId) {
        try {
            WatchPage watchPage = fetchWatch(credential, videoId);
            String html = watchPage.html();
            String cookieHeader = watchPage.cookieHeader();

            Optional<String> apiKey = extractInnertubeApiKey(html);
            if (apiKey.isEmpty()) {
                logger.info("YoutubeTranscriptClient: INNERTUBE_API_KEY introuvable pour videoId={}", videoId);
                return Optional.empty();
            }

            String playerResponseJson = fetchInnertubePlayerResponse(credential, videoId, apiKey.get(), cookieHeader);
            JsonNode playerResponse = playerResponseJson == null ? null : objectMapper.readTree(playerResponseJson);

            Optional<String> captionBaseUrl = extractCaptionBaseUrl(playerResponse);
            if (captionBaseUrl.isEmpty()) {
                logger.info("YoutubeTranscriptClient: aucune piste de sous-titres pour videoId={}", videoId);
                return Optional.empty();
            }

            String timedTextXml = fetchTimedText(credential, captionBaseUrl.get(), cookieHeader);
            List<TranscriptSegment> segments = parseTimedText(timedTextXml);
            return segments.isEmpty() ? Optional.empty() : Optional.of(segments);

        } catch (ExternalApiException e) {
            logger.warn("YoutubeTranscriptClient: source indisponible pour videoId={} : {}", videoId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.error("YoutubeTranscriptClient: erreur inattendue pour videoId={}", videoId, e);
            return Optional.empty();
        }
    }

    /**
     * HTML de {@code /watch} accompagné du header {@code Cookie} reconstitué à partir des
     * {@code Set-Cookie} de la réponse — nécessaire pour que l'appel Innertube qui suit renvoie un
     * {@code baseUrl} de sous-titres fonctionnel (cf. javadoc de classe).
     */
    private record WatchPage(String html, String cookieHeader) {
    }

    private WatchPage fetchWatch(ApiCredentialDTO credential, String videoId) {
        return getWebClient(credential).get()
                .uri(uriBuilder -> uriBuilder.path(WATCH_PATH).queryParam("v", videoId).build())
                .exchangeToMono(response -> {
                    if (response.statusCode().is4xxClientError() || response.statusCode().is5xxServerError()) {
                        return response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new ExternalApiException(
                                        "YouTube " + response.statusCode() + ": " + body)));
                    }
                    String cookieHeader = buildCookieHeader(response.headers().header(HttpHeaders.SET_COOKIE));
                    return response.bodyToMono(String.class)
                            .map(html -> new WatchPage(html, cookieHeader));
                })
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    /**
     * Reconstitue un header {@code Cookie} à partir des headers {@code Set-Cookie} d'une réponse
     * (ne garde que la paire {@code name=value}, ignore attributs {@code Path}/{@code Domain}/etc.).
     * {@code null} si aucun cookie — dans ce cas l'appelant n'ajoute simplement pas le header.
     */
    static String buildCookieHeader(List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return null;
        }
        String joined = setCookieHeaders.stream()
                .map(header -> header.split(";", 2)[0].trim())
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining("; "));
        return joined.isBlank() ? null : joined;
    }

    private String fetchInnertubePlayerResponse(ApiCredentialDTO credential, String videoId, String apiKey, String cookieHeader) {
        return getWebClient(credential).post()
                .uri(uriBuilder -> uriBuilder.path(INNERTUBE_PLAYER_PATH).queryParam("key", apiKey).build())
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set(HttpHeaders.COOKIE, cookieHeader);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(buildInnertubeRequestBody(videoId))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    private String fetchTimedText(ApiCredentialDTO credential, String baseUrl, String cookieHeader) {
        return getWebClient(credential).get()
                .uri(URI.create(baseUrl))
                .headers(headers -> {
                    if (cookieHeader != null && !cookieHeader.isBlank()) {
                        headers.set(HttpHeaders.COOKIE, cookieHeader);
                    }
                })
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    private String buildInnertubeRequestBody(String videoId) {
        ObjectNode client = objectMapper.createObjectNode();
        client.put("clientName", INNERTUBE_CLIENT_NAME);
        client.put("clientVersion", INNERTUBE_CLIENT_VERSION);

        ObjectNode context = objectMapper.createObjectNode();
        context.set("client", client);

        ObjectNode body = objectMapper.createObjectNode();
        body.set("context", context);
        body.put("videoId", videoId);

        return body.toString();
    }

    /**
     * Extrait {@code INNERTUBE_API_KEY} du HTML de {@code /watch} par regex — valeur statique par
     * page (pas liée à la dégradation des caption URLs observée avec l'ancien mécanisme). Défensif :
     * {@link Optional#empty()} si absente, jamais d'exception.
     */
    static Optional<String> extractInnertubeApiKey(String html) {
        if (html == null) {
            return Optional.empty();
        }
        Matcher matcher = API_KEY_PATTERN.matcher(html);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /**
     * Choisit la piste de sous-titres préférée (fr en priorité, sinon la première disponible) dans
     * la réponse JSON de l'API Innertube. Package-private + statique-friendly pour être testable
     * sur fixture, sans appel réseau.
     */
    static Optional<String> extractCaptionBaseUrl(JsonNode playerResponse) {
        if (playerResponse == null) {
            return Optional.empty();
        }

        JsonNode tracks = playerResponse.path("captions").path("playerCaptionsTracklistRenderer").path("captionTracks");
        if (!tracks.isArray() || tracks.isEmpty()) {
            return Optional.empty();
        }

        JsonNode chosen = null;
        for (JsonNode track : tracks) {
            if (PREFERRED_LANGUAGE.equals(track.path("languageCode").asText(null))) {
                chosen = track;
                break;
            }
        }
        if (chosen == null) {
            chosen = tracks.get(0);
        }

        String baseUrl = chosen.path("baseUrl").asText(null);
        return Optional.ofNullable(baseUrl).filter(u -> !u.isBlank()).map(YoutubeTranscriptClient::stripFmtParam);
    }

    /**
     * Retire le paramètre {@code fmt} (ex. {@code fmt=srv3}) d'un {@code baseUrl} de sous-titres.
     * Validé empiriquement le 2026-07-12 : quand l'API Innertube renvoie un {@code baseUrl} avec
     * {@code &fmt=srv3}, la réponse est dans un schéma XML différent ({@code <p t= d=><s>...})
     * que {@link #parseTimedText} ne sait pas lire (0 segment parsé sur une réponse pourtant valide
     * de 136 508 caractères). Sans paramètre {@code fmt}, YouTube renvoie le format simple
     * {@code <transcript><text start dur>...} attendu — confirmé avec la même vidéo donnant alors
     * 526 segments, identique au nombre obtenu côté {@code youtube-transcript-api} (Python).
     */
    static String stripFmtParam(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        String stripped = baseUrl.replaceAll("(?i)([&?])fmt=[^&]*&?", "$1");
        stripped = stripped.replaceAll("[&?]$", "");
        return stripped;
    }

    /**
     * Parse la réponse XML {@code <transcript><text start dur>...</text></transcript>}. Défensif :
     * toute anomalie (XML invalide, aucun segment) → liste vide, jamais d'exception qui remonte.
     */
    static List<TranscriptSegment> parseTimedText(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList textNodes = doc.getElementsByTagName("text");
            List<TranscriptSegment> segments = new ArrayList<>();

            for (int i = 0; i < textNodes.getLength(); i++) {
                Element el = (Element) textNodes.item(i);

                double start = parseDoubleSafe(el.getAttribute("start"));
                double dur = parseDoubleSafe(el.getAttribute("dur"));
                // Double-échappement observé côté YouTube (ex. "&amp;#39;" en texte brut après un
                // premier passage XML) : un 2ᵉ passage de dé-échappement HTML est nécessaire.
                String text = Parser.unescapeEntities(el.getTextContent(), false);

                if (text != null && !text.isBlank()) {
                    segments.add(new TranscriptSegment(text, start, dur));
                }
            }

            return segments;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double parseDoubleSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("YouTube 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("YouTube 5xx: " + body));
    }
}
