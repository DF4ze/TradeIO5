package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Client RSS générique pour une chaîne YouTube (docs/etude-veille-media-youtube.md §4, Lot 1).
 * Endpoint public sans authentification, même patron que {@code ForexFactoryCalendarClient} :
 * dégradation gracieuse sur erreur ({@code List.of()}), jamais d'exception qui remonte.
 * <p>
 * Réponse = flux Atom XML (pas JSON) : parsé avec {@code javax.xml.parsers.DocumentBuilder}
 * (déjà dans le JDK) plutôt qu'une lib RSS dédiée, pour un besoin aussi simple.
 */
@Component
public class YoutubeRssClient extends AbstractExternalIndicator {

    private static final String PATH = "/feeds/videos.xml";

    private final Logger logger = LoggerFactory.getLogger(YoutubeRssClient.class);

    public List<YoutubeVideoRef> fetchLatestVideos(ApiCredentialDTO credential, String channelId) {
        try {
            String xml = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path(PATH).queryParam("channel_id", channelId).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            return parseFeed(xml);
        } catch (ExternalApiException e) {
            logger.warn("YoutubeRssClient: flux RSS indisponible pour channelId={} : {}", channelId, e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.error("YoutubeRssClient: erreur inattendue pour channelId={}", channelId, e);
            return List.of();
        }
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire sur fixture (patron
     * {@code ForexFactoryCalendarClient.toMacroEvents}).
     */
    static List<YoutubeVideoRef> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            NodeList entries = doc.getElementsByTagNameNS("*", "entry");
            List<YoutubeVideoRef> videos = new ArrayList<>();

            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);

                String videoId = firstChildText(entry, "videoId");
                String title = firstChildText(entry, "title");
                String published = firstChildText(entry, "published");

                if (videoId == null || videoId.isBlank() || title == null || published == null) {
                    continue;
                }

                try {
                    Instant publishedAt = OffsetDateTime.parse(published).toInstant();
                    videos.add(new YoutubeVideoRef(videoId, title, publishedAt));
                } catch (Exception ignored) {
                    // Date mal formée sur cette entrée précise : on l'ignore plutôt que de faire
                    // échouer le parsing de tout le flux.
                }
            }

            return videos;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String firstChildText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("YouTube RSS 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("YouTube RSS 5xx: " + body));
    }
}
