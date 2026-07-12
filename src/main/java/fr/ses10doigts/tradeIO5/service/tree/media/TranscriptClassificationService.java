package fr.ses10doigts.tradeIO5.service.tree.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ClassificationResult;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.service.connector.OpenAIService;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Passe 1 du pipeline de veille média (docs/etude-veille-media-youtube.md §2, Lot 2 item A) :
 * classification légère (titre + extrait tronqué du transcript) pour filtrer le contenu
 * hors-sujet avant la passe 2, coûteuse, qui ne s'exécute que sur le sous-ensemble pertinent.
 * <p>
 * Le prompt embarque lui-même son schéma JSON attendu (même style que
 * {@code AbstractAdvisor#expectedOutputBlock()}) : {@code OpenAIService} ne construit pas de
 * prompt, c'est la responsabilité de chaque site d'appel.
 */
@Service
public class TranscriptClassificationService {

    /**
     * Fenêtre (en secondes de transcript) envoyée en passe 1 — doit être strictement identique à
     * {@code tools/media_watch/probe_transcript.py --excerpt-seconds} (défaut 120s, validé
     * empiriquement, docs/etude-veille-media-youtube.md §6).
     */
    static final int EXCERPT_SECONDS = 120;

    private static final String CALL_SITE = "media-watch:classification";

    private final Logger logger = LoggerFactory.getLogger(TranscriptClassificationService.class);

    private final OpenAIService openAIService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TranscriptClassificationService(OpenAIService openAIService) {
        this.openAIService = openAIService;
    }

    public ClassificationResult classify(VideoContentEntity video) {
        List<TranscriptSegment> segments = deserializeTranscript(video.getTranscript());
        String excerpt = buildExcerpt(segments);
        String prompt = buildPrompt(video.getTitle(), excerpt);

        return openAIService.ask(prompt, LlmTier.LOW, CALL_SITE, ClassificationResult.class);
    }

    /**
     * Extrait basé sur le temps couvert (segments dont {@code startSeconds < EXCERPT_SECONDS}),
     * pas un nombre de caractères fixe — même logique que {@code probe_transcript.py.summarize()}.
     */
    static String buildExcerpt(List<TranscriptSegment> segments) {
        return segments.stream()
                .filter(s -> s.startSeconds() < EXCERPT_SECONDS)
                .map(TranscriptSegment::text)
                .collect(Collectors.joining(" "));
    }

    static String buildPrompt(String title, String excerpt) {
        return """
                Tu es un assistant qui classe des vidéos YouTube crypto/macro à partir de leur titre \
                et d'un extrait du début de leur transcript.

                Titre : %s

                Extrait (début de vidéo, ~2 minutes de transcript) :
                %s

                Détermine si cette vidéo est une analyse de marché crypto/macro (ex: BTC, ETH, \
                indicateurs techniques, contexte macro-économique) ou un contenu à thème sans rapport \
                avec de l'analyse de marché (ex: tutoriel DeFi pas à pas, psychologie/stratégie de \
                trading générale, autre sujet).

                Réponds STRICTEMENT au format JSON :
                {
                  "isMarketRelevant": true|false,
                  "category": "market_analysis|off_topic_defi_tutorial|off_topic_psychology|other"
                }
                """.formatted(title, excerpt);
    }

    private List<TranscriptSegment> deserializeTranscript(String transcriptJson) {
        if (transcriptJson == null || transcriptJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(transcriptJson, new TypeReference<List<TranscriptSegment>>() {});
        } catch (JsonProcessingException e) {
            logger.error("TranscriptClassificationService: transcript JSON illisible", e);
            return List.of();
        }
    }
}
