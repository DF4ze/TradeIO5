package fr.ses10doigts.tradeIO5.service.tree.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractionResult;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.LlmTier;
import fr.ses10doigts.tradeIO5.model.enumerate.media.ClaimHorizon;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
import fr.ses10doigts.tradeIO5.service.connector.OpenAIService;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Passe 2 du pipeline de veille média (docs/etude-veille-media-youtube.md §2, Lot 2 item B) :
 * extraction de claims structurés à partir du transcript complet (pas de troncature ici,
 * contrairement à {@link TranscriptClassificationService}) — uniquement déclenchée pour les
 * vidéos jugées pertinentes par la passe 1.
 */
@Service
public class TranscriptClaimExtractionService {

    private static final String CALL_SITE = "media-watch:extraction";

    private final Logger logger = LoggerFactory.getLogger(TranscriptClaimExtractionService.class);

    private final OpenAIService openAIService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TranscriptClaimExtractionService(OpenAIService openAIService) {
        this.openAIService = openAIService;
    }

    public ExtractionResult extractClaims(VideoContentEntity video) {
        List<TranscriptSegment> segments = deserializeTranscript(video.getTranscript());
        String fullText = segments.stream().map(TranscriptSegment::text).collect(Collectors.joining(" "));
        String prompt = buildPrompt(video.getTitle(), fullText);

        return openAIService.ask(prompt, LlmTier.MEDIUM, CALL_SITE, ExtractionResult.class);
    }

    static String buildPrompt(String title, String fullTranscript) {
        // Valeurs autorisées pour sentiment/horizon précisées explicitement (nom exact, casse
        // incluse) pour éviter tout écart qui casserait le valueOf(...) à la persistance
        // (docs/prompt-implementation-veille-media-full.md, Lot 2 item B).
        return """
                Tu es un assistant qui extrait des affirmations de marché structurées à partir du \
                transcript complet d'une vidéo d'analyse crypto/macro.

                Titre : %s

                Transcript complet :
                %s

                Extrais une liste de 0 à N affirmations de marché ("claims"), chacune avec :
                - symbol : le symbole concerné (ex: "BTC", "ETH", "SOL")
                - sentiment : exactement l'une de ces valeurs (respecter la casse) : "BULLISH", "BEARISH", "NEUTRAL"
                - horizon : exactement l'une de ces valeurs (respecter la casse) : "COURT_TERME", "MOYEN_TERME", "LONG_TERME"
                - confidence : niveau de confiance entre 0.0 et 1.0
                - excerpt : un court extrait du texte source justifiant l'affirmation (audit/traçabilité)

                Réponds STRICTEMENT au format JSON :
                {
                  "claims": [
                    {
                      "symbol": "BTC",
                      "sentiment": "BULLISH|BEARISH|NEUTRAL",
                      "horizon": "COURT_TERME|MOYEN_TERME|LONG_TERME",
                      "confidence": 0.0,
                      "excerpt": "..."
                    }
                  ]
                }
                Si aucune affirmation exploitable n'est trouvée, réponds {"claims": []}.
                """.formatted(title, fullTranscript);
    }

    private List<TranscriptSegment> deserializeTranscript(String transcriptJson) {
        if (transcriptJson == null || transcriptJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(transcriptJson, new TypeReference<List<TranscriptSegment>>() {});
        } catch (JsonProcessingException e) {
            logger.error("TranscriptClaimExtractionService: transcript JSON illisible", e);
            return List.of();
        }
    }

    /** Exposé pour {@code TranscriptExtractionService} : mapping String -> enum isolé par claim. */
    public static SignalType parseSentiment(String raw) {
        return SignalType.valueOf(raw);
    }

    /** Exposé pour {@code TranscriptExtractionService} : mapping String -> enum isolé par claim. */
    public static ClaimHorizon parseHorizon(String raw) {
        return ClaimHorizon.valueOf(raw);
    }
}
