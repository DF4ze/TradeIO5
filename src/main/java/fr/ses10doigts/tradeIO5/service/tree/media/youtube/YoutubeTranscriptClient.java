package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
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

/**
 * Client transcript générique pour une vidéo YouTube (docs/etude-veille-media-youtube.md §4, Lot 1).
 * <p>
 * Réplique le mécanisme utilisé en interne par {@code youtube-transcript-api}
 * (tools/media_watch/probe_transcript.py) faute de lib Java équivalente mature :
 * <ol>
 *   <li>{@code GET /watch?v={videoId}} → HTML brut</li>
 *   <li>Extraction du bloc {@code var ytInitialPlayerResponse = {...};} (comptage d'accolades,
 *       pas un simple {@code indexOf(";")} qui couperait sur un ';' interne à une chaîne)</li>
 *   <li>Choix de la piste {@code captionTracks[]} en {@code fr} en priorité, sinon la première</li>
 *   <li>{@code baseUrl} est une URL absolue (domaine {@code googlevideo.com} en prod) : appelée via
 *       {@code .uri(URI.create(baseUrl))} sur le même {@code WebClient} (accepte une URI absolue
 *       malgré le {@code baseUrl} configuré, cf. {@code AbstractExternalIndicator})</li>
 *   <li>Réponse XML {@code <transcript><text start dur>...</text></transcript>}, dé-échappée via
 *       {@code org.jsoup.parser.Parser.unescapeEntities} (jsoup déjà une dépendance du projet)</li>
 * </ol>
 * <b>Fragilité assumée</b> : dépend de la structure interne non documentée des pages YouTube.
 * Parsing défensif — tout champ absent ou toute anomalie → {@link Optional#empty()}, jamais
 * d'exception qui remonte jusqu'au job planifié.
 */
@Component
public class YoutubeTranscriptClient extends AbstractExternalIndicator {

    private static final String WATCH_PATH = "/watch";
    private static final String PLAYER_RESPONSE_MARKER = "ytInitialPlayerResponse";
    private static final String PREFERRED_LANGUAGE = "fr";

    private final Logger logger = LoggerFactory.getLogger(YoutubeTranscriptClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<List<TranscriptSegment>> fetchTranscript(ApiCredentialDTO credential, String videoId) {
        try {
            String html = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path(WATCH_PATH).queryParam("v", videoId).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            Optional<String> captionBaseUrl = extractCaptionBaseUrl(html);
            if (captionBaseUrl.isEmpty()) {
                logger.info("YoutubeTranscriptClient: aucune piste de sous-titres pour videoId={}", videoId);
                return Optional.empty();
            }

            String timedTextXml = getWebClient(credential).get()
                    .uri(URI.create(captionBaseUrl.get()))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

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
     * Extrait la baseUrl de la piste de sous-titres préférée (fr en priorité, sinon la première
     * disponible) depuis le HTML de la page /watch. Package-private + statique-friendly pour être
     * testable sur fixture, sans appel réseau.
     */
    Optional<String> extractCaptionBaseUrl(String html) {
        String json = extractJsonObject(html, PLAYER_RESPONSE_MARKER);
        if (json == null) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode tracks = root.path("captions").path("playerCaptionsTracklistRenderer").path("captionTracks");

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
            return Optional.ofNullable(baseUrl).filter(u -> !u.isBlank());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Repère {@code marker = {...};} dans le HTML et extrait le JSON qui suit par comptage
     * d'accolades (en ignorant les accolades à l'intérieur de chaînes JSON), jusqu'à la fermeture
     * de l'objet racine — ne coupe jamais sur un ';' interne à une chaîne.
     */
    static String extractJsonObject(String html, String marker) {
        if (html == null) {
            return null;
        }

        int markerIdx = html.indexOf(marker);
        if (markerIdx < 0) {
            return null;
        }

        int braceStart = html.indexOf('{', markerIdx);
        if (braceStart < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = braceStart; i < html.length(); i++) {
            char c = html.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(braceStart, i + 1);
                }
            }
        }

        return null;
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
                .map(body -> new ExternalApiException("YouTube watch page 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("YouTube watch page 5xx: " + body));
    }
}
