package fr.ses10doigts.tradeIO5.service.tree.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ClassificationResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractedClaim;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractionResult;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeRssClient;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeTranscriptClient;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeVideoRef;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test manuel réseau réel — désactivé par défaut ({@link Disabled}). Évalue "grandeur nature"
 * l'efficacité du pipeline de classification (passe 1) et d'extraction de claims (passe 2) sur les
 * toutes dernières vraies vidéos Cryptolyze, sans passer par le job planifié ni la persistance en
 * base (appel direct des services sur des {@link VideoContentEntity} transitoires) — donc aucun
 * effet de bord en base.
 * <p>
 * Fait de vrais appels réseau (YouTube + LLM OpenAI) : à activer ponctuellement en local via le
 * pattern ssh-gateway (cf. mémoire "ssh-gateway execLocalCommand quirks").
 */
@Disabled("Test manuel réseau réel — activer ponctuellement en local pour évaluer le pipeline sur de vraies vidéos")
@DisplayName("Media - Pipeline complet (réseau réel, manuel)")
@SpringBootTest
class MediaWatchRealPipelineTest {

    private static final String CRYPTOLYZE_CHANNEL_ID = "UCuXgThwkFpefb41aKWKqrOw";

    @Autowired
    private YoutubeRssClient rssClient;

    @Autowired
    private YoutubeTranscriptClient transcriptClient;

    @Autowired
    private TranscriptClassificationService classificationService;

    @Autowired
    private TranscriptClaimExtractionService extractionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Classification + extraction sur les 2 dernières vraies vidéos Cryptolyze")
    void evaluateClassificationAndExtractionOnLatestVideos() throws Exception {
        // Pas de MediaCredentialResolver ici : la credential YOUTUBE en base n'est seedée que sous
        // le profil "dev" (ApiCredentialInitializer), absent en profil "test". YOUTUBE ne nécessite
        // de toute façon aucune clé API (endpoints publics RSS/watch) — même construction directe
        // que YoutubeManualNetworkTest.
        ApiCredentialDTO credential = new ApiCredentialDTO(WebProviderCode.YOUTUBE, "", "", "https://www.youtube.com");

        List<YoutubeVideoRef> videos = rssClient.fetchLatestVideos(credential, CRYPTOLYZE_CHANNEL_ID);
        assertTrue(videos.size() >= 2, "Le flux RSS doit renvoyer au moins 2 vidéos pour ce test");

        List<YoutubeVideoRef> sorted = videos.stream()
                .sorted(Comparator.comparing(YoutubeVideoRef::publishedAt).reversed())
                .toList();

        System.out.println("=== Vidéos disponibles (les plus récentes en premier) ===");
        sorted.forEach(v -> System.out.println("  " + v.publishedAt() + " -- " + v.title() + " (" + v.videoId() + ")"));

        YoutubeVideoRef latest = sorted.get(0);
        YoutubeVideoRef previous = sorted.get(1);

        System.out.println();
        System.out.println("=== Vidéo la plus récente (attendue : hors analyse de marché) ===");
        ClassificationResult latestClassification = classifyVideo(credential, latest);

        System.out.println();
        System.out.println("=== Vidéo précédente / d'hier (attendue : analyse de marché) ===");
        VideoContentEntity previousVideo = fetchAndBuildVideo(credential, previous);
        if (previousVideo == null) {
            fail("Transcript introuvable pour la vidéo précédente (" + previous.videoId() + ") — impossible d'évaluer");
        }
        ClassificationResult previousClassification = classificationService.classify(previousVideo);
        System.out.println("Classification : isMarketRelevant=" + previousClassification.isMarketRelevant()
                + " category=" + previousClassification.category());

        System.out.println();
        System.out.println("=== Extraction des claims sur la vidéo d'hier ===");
        if (previousClassification.isMarketRelevant()) {
            ExtractionResult extraction = extractionService.extractClaims(previousVideo);
            System.out.println("Nombre de claims extraits : " + extraction.claims().size());
            for (ExtractedClaim claim : extraction.claims()) {
                System.out.println("  - symbol=" + claim.symbol()
                        + " sentiment=" + claim.sentiment()
                        + " horizon=" + claim.horizon()
                        + " confidence=" + claim.confidence()
                        + " excerpt=\"" + claim.excerpt() + "\"");
            }
        } else {
            System.out.println("(non exécuté : la vidéo d'hier n'a pas été classée comme pertinente — voir résultat ci-dessus)");
        }

        System.out.println();
        System.out.println("=== Bilan ===");
        System.out.println("Vidéo récente (" + latest.title() + ") -> isMarketRelevant=" + latestClassification.isMarketRelevant()
                + " (attendu: false)");
        System.out.println("Vidéo d'hier (" + previous.title() + ") -> isMarketRelevant=" + previousClassification.isMarketRelevant()
                + " (attendu: true)");

        assertTrue(!latestClassification.isMarketRelevant(),
                "La vidéo la plus récente aurait dû être classée comme hors analyse de marché");
        assertTrue(previousClassification.isMarketRelevant(),
                "La vidéo d'hier aurait dû être classée comme analyse de marché");
    }

    private ClassificationResult classifyVideo(ApiCredentialDTO credential, YoutubeVideoRef ref) throws Exception {
        VideoContentEntity video = fetchAndBuildVideo(credential, ref);
        if (video == null) {
            fail("Transcript introuvable pour la vidéo '" + ref.title() + "' (" + ref.videoId() + ")");
        }

        List<TranscriptSegment> segments = objectMapper.readValue(video.getTranscript(),
                new com.fasterxml.jackson.core.type.TypeReference<List<TranscriptSegment>>() {});
        String excerpt = TranscriptClassificationService.buildExcerpt(segments);
        System.out.println("Titre : " + ref.title());
        System.out.println("Excerpt (120s) envoyé à la passe 1 : \"" + excerpt + "\"");

        ClassificationResult result = classificationService.classify(video);
        System.out.println("Classification : isMarketRelevant=" + result.isMarketRelevant() + " category=" + result.category());
        return result;
    }

    private VideoContentEntity fetchAndBuildVideo(ApiCredentialDTO credential, YoutubeVideoRef ref) throws Exception {
        Optional<List<TranscriptSegment>> transcript = transcriptClient.fetchTranscript(credential, ref.videoId());
        if (transcript.isEmpty()) {
            return null;
        }
        return VideoContentEntity.builder()
                .videoId(ref.videoId())
                .title(ref.title())
                .publishedAt(ref.publishedAt())
                .transcript(objectMapper.writeValueAsString(transcript.get()))
                .status(VideoContentStatus.PENDING)
                .build();
    }
}
