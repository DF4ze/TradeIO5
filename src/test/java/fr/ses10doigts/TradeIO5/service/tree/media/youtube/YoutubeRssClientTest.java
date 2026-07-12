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
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Media - YoutubeRssClient")
class YoutubeRssClientTest {

    // Fixture Atom simplifiée mais structurellement fidèle au vrai flux
    // https://www.youtube.com/feeds/videos.xml?channel_id=... (namespace yt: sur videoId/channelId).
    private static final String SAMPLE_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
              <link rel="self" href="https://www.youtube.com/feeds/videos.xml?channel_id=UCuXgThwkFpefb41aKWKqrOw"/>
              <id>yt:channel:UCuXgThwkFpefb41aKWKqrOw</id>
              <yt:channelId>UCuXgThwkFpefb41aKWKqrOw</yt:channelId>
              <title>Cryptolyze</title>
              <entry>
                <id>yt:video:ABCDEF12345</id>
                <yt:videoId>ABCDEF12345</yt:videoId>
                <yt:channelId>UCuXgThwkFpefb41aKWKqrOw</yt:channelId>
                <title>Analyse marché BTC/ETH - semaine du 6 juillet</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=ABCDEF12345"/>
                <published>2026-07-06T10:00:00+00:00</published>
                <updated>2026-07-06T12:00:00+00:00</updated>
              </entry>
              <entry>
                <id>yt:video:ZZZZZZ99999</id>
                <yt:videoId>ZZZZZZ99999</yt:videoId>
                <yt:channelId>UCuXgThwkFpefb41aKWKqrOw</yt:channelId>
                <title>Pas a Pas DeFi - Episode 12</title>
                <link rel="alternate" href="https://www.youtube.com/watch?v=ZZZZZZ99999"/>
                <published>2026-07-03T09:00:00+00:00</published>
                <updated>2026-07-03T09:30:00+00:00</updated>
              </entry>
            </feed>
            """;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("parseFeed() extrait videoId/title/publishedAt pour chaque <entry>")
    void parseFeed_extractsAllEntries() {
        List<YoutubeVideoRef> videos = YoutubeRssClient.parseFeed(SAMPLE_FEED);

        assertEquals(2, videos.size());

        YoutubeVideoRef first = videos.get(0);
        assertEquals("ABCDEF12345", first.videoId());
        assertEquals("Analyse marché BTC/ETH - semaine du 6 juillet", first.title());
        assertEquals(OffsetDateTime.parse("2026-07-06T10:00:00+00:00").toInstant(), first.publishedAt());

        YoutubeVideoRef second = videos.get(1);
        assertEquals("ZZZZZZ99999", second.videoId());
        assertEquals("Pas a Pas DeFi - Episode 12", second.title());
    }

    @Test
    @DisplayName("parseFeed() retourne une liste vide sur un flux vide/mal formé, sans exception")
    void parseFeed_returnsEmpty_onBlankOrMalformed() {
        assertTrue(YoutubeRssClient.parseFeed(null).isEmpty());
        assertTrue(YoutubeRssClient.parseFeed("").isEmpty());
        assertTrue(YoutubeRssClient.parseFeed("<not-xml").isEmpty());
    }

    @Test
    @DisplayName("parseFeed() ignore une entrée sans videoId, sans faire échouer les autres")
    void parseFeed_ignoresEntryWithoutVideoId() {
        String feed = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <title>Sans videoId</title>
                    <published>2026-07-06T10:00:00+00:00</published>
                  </entry>
                  <entry>
                    <yt:videoId>VALID1</yt:videoId>
                    <title>Valide</title>
                    <published>2026-07-06T10:00:00+00:00</published>
                  </entry>
                </feed>
                """;

        List<YoutubeVideoRef> videos = YoutubeRssClient.parseFeed(feed);

        assertEquals(1, videos.size());
        assertEquals("VALID1", videos.getFirst().videoId());
    }

    @Test
    @DisplayName("fetchLatestVideos() lit correctement une réponse HTTP réelle (fixture servie en local)")
    void fetchLatestVideos_success() throws IOException {
        server = startServer(200, SAMPLE_FEED);

        YoutubeRssClient client = new YoutubeRssClient();
        List<YoutubeVideoRef> videos = client.fetchLatestVideos(credentialFor(server), "UCuXgThwkFpefb41aKWKqrOw");

        assertEquals(2, videos.size());
    }

    @Test
    @DisplayName("fetchLatestVideos() retombe sur une liste vide sur 500, sans exception")
    void fetchLatestVideos_returnsEmpty_on5xx() throws IOException {
        server = startServer(500, "boom");

        YoutubeRssClient client = new YoutubeRssClient();
        List<YoutubeVideoRef> videos = client.fetchLatestVideos(credentialFor(server), "UCuXgThwkFpefb41aKWKqrOw");

        assertTrue(videos.isEmpty());
    }

    private ApiCredentialDTO credentialFor(HttpServer srv) {
        return new ApiCredentialDTO(WebProviderCode.YOUTUBE, "", "", "http://127.0.0.1:" + srv.getAddress().getPort());
    }

    private HttpServer startServer(int status, String body) throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        srv.createContext("/feeds/videos.xml", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/atom+xml");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        srv.start();
        return srv;
    }
}
