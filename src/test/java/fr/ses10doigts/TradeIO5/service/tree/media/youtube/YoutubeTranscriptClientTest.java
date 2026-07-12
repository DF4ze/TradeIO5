package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("extractInnertubeApiKey() extrait la clé depuis le HTML de /watch")
    void extractInnertubeApiKey_extractsFromHtml() {
        String html = "<html><script>var ytcfg={\"INNERTUBE_API_KEY\":\"AIzaSyABC123_-def\",\"other\":1};</script></html>";

        Optional<String> apiKey = YoutubeTranscriptClient.extractInnertubeApiKey(html);

        assertTrue(apiKey.isPresent());
        assertEquals("AIzaSyABC123_-def", apiKey.get());
    }

    @Test
    @DisplayName("extractInnertubeApiKey() retourne Optional.empty() si absente ou HTML null")
    void extractInnertubeApiKey_empty_whenAbsent() {
        assertFalse(YoutubeTranscriptClient.extractInnertubeApiKey("<html>rien ici</html>").isPresent());
        assertFalse(YoutubeTranscriptClient.extractInnertubeApiKey(null).isPresent());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() choisit la piste fr en priorité si plusieurs langues disponibles")
    void extractCaptionBaseUrl_prefersFrench() throws Exception {
        JsonNode playerResponse = objectMapper.readTree("""
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"https://example.com/timedtext?lang=en","languageCode":"en"},
                  {"baseUrl":"https://example.com/timedtext?lang=fr","languageCode":"fr"}
                ]}}}
                """);

        Optional<String> baseUrl = YoutubeTranscriptClient.extractCaptionBaseUrl(playerResponse);

        assertTrue(baseUrl.isPresent());
        assertEquals("https://example.com/timedtext?lang=fr", baseUrl.get());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() retombe sur la première piste dispo si pas de fr")
    void extractCaptionBaseUrl_fallsBackToFirstTrack_whenNoFrench() throws Exception {
        JsonNode playerResponse = objectMapper.readTree("""
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"https://example.com/timedtext?lang=en","languageCode":"en"},
                  {"baseUrl":"https://example.com/timedtext?lang=es","languageCode":"es"}
                ]}}}
                """);

        Optional<String> baseUrl = YoutubeTranscriptClient.extractCaptionBaseUrl(playerResponse);

        assertTrue(baseUrl.isPresent());
        assertEquals("https://example.com/timedtext?lang=en", baseUrl.get());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() retourne Optional.empty() quand la vidéo n'a aucun sous-titre")
    void extractCaptionBaseUrl_empty_whenNoCaptions() throws Exception {
        JsonNode playerResponse = objectMapper.readTree("""
                {"videoDetails":{"videoId":"ABCDEF12345"}}
                """);

        assertFalse(YoutubeTranscriptClient.extractCaptionBaseUrl(playerResponse).isPresent());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() retourne Optional.empty() sur une réponse null")
    void extractCaptionBaseUrl_empty_whenNullResponse() {
        assertFalse(YoutubeTranscriptClient.extractCaptionBaseUrl(null).isPresent());
    }

    @Test
    @DisplayName("extractCaptionBaseUrl() retire le paramètre fmt (ex. fmt=srv3) du baseUrl choisi")
    void extractCaptionBaseUrl_stripsFmtParam() throws Exception {
        JsonNode playerResponse = objectMapper.readTree("""
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"https://example.com/timedtext?v=x&kind=asr&lang=fr&fmt=srv3","languageCode":"fr"}
                ]}}}
                """);

        Optional<String> baseUrl = YoutubeTranscriptClient.extractCaptionBaseUrl(playerResponse);

        assertTrue(baseUrl.isPresent());
        assertEquals("https://example.com/timedtext?v=x&kind=asr&lang=fr", baseUrl.get());
    }

    @Test
    @DisplayName("stripFmtParam() retire fmt en milieu, en fin d'URL et gère l'absence de fmt")
    void stripFmtParam_variousPositions() {
        assertEquals("https://x.com/t?a=1&b=2",
                YoutubeTranscriptClient.stripFmtParam("https://x.com/t?a=1&fmt=srv3&b=2"));
        assertEquals("https://x.com/t?a=1&b=2",
                YoutubeTranscriptClient.stripFmtParam("https://x.com/t?a=1&b=2&fmt=srv3"));
        assertEquals("https://x.com/t?a=1&b=2",
                YoutubeTranscriptClient.stripFmtParam("https://x.com/t?a=1&b=2"));
        assertEquals(null, YoutubeTranscriptClient.stripFmtParam(null));
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
    @DisplayName("buildCookieHeader() reconstitue un header Cookie depuis des Set-Cookie (ignore les attributs)")
    void buildCookieHeader_joinsNameValuePairs() {
        String cookieHeader = YoutubeTranscriptClient.buildCookieHeader(List.of(
                "VISITOR_INFO1_LIVE=abc123; Path=/; Domain=.youtube.com; Secure",
                "YSC=def456; Path=/"
        ));

        assertEquals("VISITOR_INFO1_LIVE=abc123; YSC=def456", cookieHeader);
    }

    @Test
    @DisplayName("buildCookieHeader() retourne null quand la liste est vide ou nulle")
    void buildCookieHeader_null_whenNoCookies() {
        assertEquals(null, YoutubeTranscriptClient.buildCookieHeader(null));
        assertEquals(null, YoutubeTranscriptClient.buildCookieHeader(List.of()));
    }

    @Test
    @DisplayName("fetchTranscript() bout-en-bout : /watch -> clé Innertube -> POST player -> timedtext -> segments")
    void fetchTranscript_endToEnd_returnsSegments() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String timedTextUrl = "http://127.0.0.1:" + port + "/timedtext?lang=fr";

        String watchHtml = "<html><script>var ytcfg={\"INNERTUBE_API_KEY\":\"TEST_API_KEY\"};</script></html>";
        String playerResponseJson = """
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"%s","languageCode":"fr"}
                ]}}}
                """.formatted(timedTextUrl);

        registerContext(server, "/watch", 200, watchHtml, "text/html");
        registerContext(server, "/youtubei/v1/player", 200, playerResponseJson, "application/json");
        registerContext(server, "/timedtext", 200, SAMPLE_TIMEDTEXT, "text/xml");
        server.start();

        YoutubeTranscriptClient client = new YoutubeTranscriptClient();
        Optional<List<TranscriptSegment>> result = client.fetchTranscript(credentialFor(server), "ABCDEF12345");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().size());
        assertEquals("Bonjour 'a tous' aujourd'hui", result.get().getFirst().text());
    }

    @Test
    @DisplayName("fetchTranscript() propage les cookies de /watch vers l'appel Innertube (POST player)")
    void fetchTranscript_propagatesCookiesFromWatchToInnertube() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String timedTextUrl = "http://127.0.0.1:" + port + "/timedtext?lang=fr";

        String watchHtml = "<html><script>var ytcfg={\"INNERTUBE_API_KEY\":\"TEST_API_KEY\"};</script></html>";
        String playerResponseJson = """
                {"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                  {"baseUrl":"%s","languageCode":"fr"}
                ]}}}
                """.formatted(timedTextUrl);

        byte[] watchBytes = watchHtml.getBytes(StandardCharsets.UTF_8);
        server.createContext("/watch", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.getResponseHeaders().add("Set-Cookie", "VISITOR_INFO1_LIVE=abc123; Path=/; Secure");
            exchange.getResponseHeaders().add("Set-Cookie", "YSC=def456; Path=/");
            exchange.sendResponseHeaders(200, watchBytes.length);
            exchange.getResponseBody().write(watchBytes);
            exchange.close();
        });

        List<String> capturedCookieHeaders = new ArrayList<>();
        byte[] playerBytes = playerResponseJson.getBytes(StandardCharsets.UTF_8);
        server.createContext("/youtubei/v1/player", exchange -> {
            capturedCookieHeaders.add(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, playerBytes.length);
            exchange.getResponseBody().write(playerBytes);
            exchange.close();
        });

        registerContext(server, "/timedtext", 200, SAMPLE_TIMEDTEXT, "text/xml");
        server.start();

        YoutubeTranscriptClient client = new YoutubeTranscriptClient();
        Optional<List<TranscriptSegment>> result = client.fetchTranscript(credentialFor(server), "ABCDEF12345");

        assertTrue(result.isPresent());
        assertEquals(1, capturedCookieHeaders.size());
        assertEquals("VISITOR_INFO1_LIVE=abc123; YSC=def456", capturedCookieHeaders.get(0));
    }

    @Test
    @DisplayName("fetchTranscript() retourne Optional.empty() quand la vidéo n'a pas de sous-titres")
    void fetchTranscript_returnsEmpty_whenNoCaptions() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        String watchHtml = "<html><script>var ytcfg={\"INNERTUBE_API_KEY\":\"TEST_API_KEY\"};</script></html>";
        String playerResponseJson = "{\"videoDetails\":{\"videoId\":\"NOCAPTIONS\"}}";

        registerContext(server, "/watch", 200, watchHtml, "text/html");
        registerContext(server, "/youtubei/v1/player", 200, playerResponseJson, "application/json");
        server.start();

        YoutubeTranscriptClient client = new YoutubeTranscriptClient();
        Optional<List<TranscriptSegment>> result = client.fetchTranscript(credentialFor(server), "NOCAPTIONS");

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("fetchTranscript() retourne Optional.empty() quand la clé Innertube est introuvable")
    void fetchTranscript_returnsEmpty_whenNoApiKey() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        registerContext(server, "/watch", 200, "<html>rien ici</html>", "text/html");
        server.start();

        YoutubeTranscriptClient client = new YoutubeTranscriptClient();
        Optional<List<TranscriptSegment>> result = client.fetchTranscript(credentialFor(server), "NOKEY");

        assertFalse(result.isPresent());
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
