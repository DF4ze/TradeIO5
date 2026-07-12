package fr.ses10doigts.tradeIO5.service.tree.media.youtube;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test manuel réseau réel — désactivé par défaut ({@link Disabled}), à activer ponctuellement en
 * local pour valider {@link YoutubeTranscriptClient#fetchTranscript} contre une vraie vidéo YouTube.
 * <p>
 * Utile après toute modification du mécanisme Innertube (cf. javadoc de classe de
 * {@code YoutubeTranscriptClient}), qui dépend d'une API interne non documentée susceptible de
 * changer sans préavis. Validé fonctionnel le 2026-07-12 (526 segments FR récupérés sur la vidéo de
 * test, cohérent avec {@code tools/media_watch/probe_transcript.py}).
 */
@Disabled("Test manuel réseau réel — activer ponctuellement en local pour valider contre une vraie vidéo YouTube")
@DisplayName("Media - YouTube (réseau réel, manuel)")
class YoutubeManualNetworkTest {

    private static final String CRYPTOLYZE_CHANNEL_ID = "UCuXgThwkFpefb41aKWKqrOw";
    private static final String KNOWN_VIDEO_ID = "reY0a4qwZTA";

    @Test
    @DisplayName("fetchTranscript() récupère un vrai transcript sur une vidéo connue")
    void fetchKnownVideoTranscript_realNetwork() {
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.YOUTUBE, "", "", "https://www.youtube.com");
        YoutubeTranscriptClient client = new YoutubeTranscriptClient();

        Optional<List<TranscriptSegment>> transcript = client.fetchTranscript(credential, KNOWN_VIDEO_ID);

        assertFalse(transcript.isEmpty(), "Le transcript réel doit être non vide (vidéo connue avec sous-titres)");
        System.out.println("Segments récupérés : " + transcript.get().size());
    }

    @Test
    @DisplayName("fetchLatestVideos() récupère les dernières vidéos du flux RSS Cryptolyze")
    void fetchLatestVideos_realNetwork() {
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.YOUTUBE, "", "", "https://www.youtube.com");
        YoutubeRssClient client = new YoutubeRssClient();

        List<YoutubeVideoRef> videos = client.fetchLatestVideos(credential, CRYPTOLYZE_CHANNEL_ID);

        assertFalse(videos.isEmpty(), "Le flux RSS Cryptolyze doit renvoyer au moins une vidéo");
        System.out.println("Vidéos récupérées : " + videos.size() + ", première : " + videos.get(0));
    }
}
