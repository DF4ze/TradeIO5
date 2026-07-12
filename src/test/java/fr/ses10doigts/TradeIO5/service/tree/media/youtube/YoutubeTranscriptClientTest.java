package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Media - YoutubeTranscriptClient")
class YoutubeTranscriptClientTest {

    // Fixture XML transcript (endpoint timedtext) — entités doublement échappées comme observé
    // en pratique côté YouTube (ex. "&amp;#39;" en texte brut), cf. mécanisme documenté dans
    // YoutubeTranscriptClient.
    private static final String SAMPLE_TIMEDTEXT = """
            <?xml version="1.0" encoding="utf-8" ?><transcript>\
            <text start="0.0" dur="4.32">Bonjour &amp;#39;a tous&amp;#39; aujourd'hui</text>\
            <text start="4.32" dur="3.1">Deuxieme segment</text>\
            </transcript>""";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("extractJsonObject() extrait le bloc JSON complet malgré un ';' interne à une chaîne")
    void extractJsonObject_stopsAtMatchingClosingBrace() {
        String html = """
                <html><body><script>
                var ytInitialData = {"foo":"bar; baz"};
                var ytInitialPlayerResponse = {"videoDetails":{"title":"Test; avec point-virgule"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[{"baseUrl":"https://example.com/timedtext?lang=fr","languageCode":"fr"}]}}};
                var otherVar = "not relevant; ignore";
                </script></body></html>
                """;

        String json = YoutubeTranscriptClient.extractJsonObject(html, "ytInitialPlayerResponse");

        assertEquals(
                "{\"videoDetails\":{\"title\":\"Test; avec point-virgule\"},\"captions\":{\"playerCaptionsTracklistRenderer\":{\"captionTracks\":[{\"baseUrl\":\"https://example.com/timedtext?lang=fr\",\"languageCode\":\"fr\"}]}}}",
                json
        );
    }

    @Test
    @DisplayName("extractJsonObject() retourne null si le marqueur est absent")
    void extractJsonObject_returnsNull_whenMarkerAbsent() {
        assertEquals(null, YoutubeTranscriptClient.extractJsonObject("<html></html>", "ytInitialPlayerResponse"));
        assertEquals(null, YoutubeTranscriptClient.extractJsonObject(null, "ytInitialPlayerResponse"));
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() choisit la piste fr en priorité si plusieurs langues disponibles")
    void extractCaptionBaseUrl_prefersFrench() {
        String html = playerResponseHtml("""
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"https://example.com/timedtext?lang=en","languageCode":"en"},
                  {"baseUrl":"https://example.com/timedtext?lang=fr","languageCode":"fr"}
                ]}}}
                """);

        Optional<String> baseUrl = new YoutubeTranscriptClient().extractCaptionBaseUrl(html);

        assertTrue(baseUrl.isPresent());
        assertEquals("https://example.com/timedtext?lang=fr", baseUrl.get());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() retombe sur la première piste dispo si pas de fr")
    void extractCaptionBaseUrl_fallsBackToFirstTrack_whenNoFrench() {
        String html = playerResponseHtml("""
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"https://example.com/timedtext?lang=en","languageCode":"en"},
                  {"baseUrl":"https://example.com/timedtext?lang=es","languageCode":"es"}
                ]}}}
                """);

        Optional<String> baseUrl = new YoutubeTranscriptClient().extractCaptionBaseUrl(html);

        assertTrue(baseUrl.isPresent());
        assertEquals("https://example.com/timedtext?lang=en", baseUrl.get());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() retourne Optional.empty() quand la vidéo n'a aucun sous-titre")
    void extractCaptionBaseUrl_empty_whenNoCaptions() {
        String html = playerResponseHtml("""
                {"videoDetails":{"videoId":"ABCDEF12345"}}
                """);

        Optional<String> baseUrl = new YoutubeTranscriptClient().extractCaptionBaseUrl(html);

        assertFalse(baseUrl.isPresent());
    }

    @Test
    @DisplayName("parseTimedText() parse les segments et dé-échappe les entités doublement encodées")
    void parseTimedText_parsesSegmentsAndUnescapesEntities() {
        List<TranscriptSegment> segments = YoutubeTranscriptClient.parseTimedText(SAMPLE_TIMEDTEXT);

        assertEquals(2, segments.size());

        TranscriptSegment first = segments.get(0);
        assertEquals("Bonjour 'a tous' aujourd'hui", first.text());
        assertEquals(0.0, first.startSeconds());
        assertEquals(4.32, first.durationSeconds());

        TranscriptSegment second = segments.get(1);
        assertEquals("Deuxieme segment", second.text());
        assertEquals(4.32, second.startSeconds());
    }

    @Test
    @DisplayName("parseTimedText() retourne une liste vide sur XML vide/mal formé, sans exception")
    void parseTimedText_returnsEmpty_onBlankOrMalformed() {
        assertTrue(YoutubeTranscriptClient.parseTimedText(null).isEmpty());
        assertTrue(YoutubeTranscriptClient.parseTimedText("").isEmpty());
        assertTrue(YoutubeTranscriptClient.parseTimedText("<not-xml").isEmpty());
        assertTrue(YoutubeTranscriptClient.parseTimedText("<transcript></transcript>").isEmpty());
    }

    @Test
    @DisplayName("fetchTranscript() bout-en-bout : /watch -> extraction JSON -> timedtext -> segments")
    void fetchTranscript_endToEnd_returnsSegments() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String timedTextUrl = "http://127.0.0.1:" + port + "/timedtext?lang=fr";

        String watchHtml = playerResponseHtml("""
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"%s","languageCode":"fr"}
                ]}}}
                """.formatted(timedTextUrl));

        registerContext(server, "/watch", 200, watchHtml, "text/html");
        registerContext(server, "/timedtext", 200, SAMPLE_TIMEDTEXT, "text/xml");
        server.start();

        YoutubeTranscriptClient client = new YoutubeTranscriptClient();
        Optional<List<TranscriptSegment>> result = client.fetchTranscript(credentialFor(server), "ABCDEF12345");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertEquals("Bonjour 'a tous' aujourd'hui", result.get().getFirst().text());
    }

    @Test
    @DisplayName("fetchTranscript() retourne Optional.empty() quand la vidéo n'a pas de sous-titres")
    void fetchTranscript_returnsEmpty_whenNoCaptions() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String watchHtml = playerResponseHtml("""
                {"videoDetails":{"videoId":"NOCAPTIONS"}}
                """);
        registerContext(server, "/watch", 200, watchHtml, "text/html");
        server.start();

        YoutubeTranscriptClient client = new YoutubeTranscriptClient();
        Optional<List<TranscriptSegment>> result = client.fetchTranscript(credentialFor(server), "NOCAPTIONS");

        assertFalse(result.isPresent());
    }

    private String playerResponseHtml(String jsonBody) {
        return "<html><head></head><body><script>\n" +
                "var ytInitialPlayerResponse = " + jsonBody.strip() + ";\n" +
                "</script></body></html>";
    }

    private ApiCredentialDTO credentialFor(HttpServer srv) {
        return new ApiCredentialDTO(WebProviderCode.YOUTUBE, "", "", "http://127.0.0.1:" + srv.getAddress().getPort());
    }

    private void registerContext(HttpServer srv, String path, int status, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
