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
     * Fenêtre (en secondes de transcript) envoyée en passe 1. Initialement 120s (aligné sur
     * {@code tools/media_watch/probe_transcript.py --excerpt-seconds}, docs/etude-veille-media-youtube.md
     * §6). Élargie à 300s le 2026-07-12 suite à un faux positif observé en test réel : une vidéo
     * Cryptolyze majoritairement historique ("BNB : la crypto que rien n'arrête" — histoire de CZ/
     * Binance/BNB) ouvre sur un hook volontairement orienté marché (divergence de prix, écoute
     * institutionnelle, question de "découplage") dans les 120 premières secondes, avant de
     * bifurquer vers le vrai sujet (historique) — signal insuffisant sur cette seule fenêtre pour
     * détecter le pivot. 300s laisse davantage de place pour que le pivot apparaisse dans l'extrait
     * envoyé au LLM. {@code tools/media_watch/probe_transcript.py --excerpt-seconds} n'a pas été
     * modifié en conséquence (outil de diagnostic transcript, pas de classification) — les deux
     * valeurs ne sont plus censées être strictement identiques.
     */
    static final int EXCERPT_SECONDS = 300;

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

                Extrait (début de vidéo, ~5 minutes de transcript) :
                %s

                Détermine si cette vidéo est une ANALYSE DE MARCHÉ au sens strict : un contenu qui \
                s'appuie concrètement sur au moins un des deux éléments suivants pour donner un avis \
                directionnel (haussier/baissier/neutre) à un horizon donné sur un ou plusieurs actifs :
                - des indicateurs techniques nommés et chiffrés (ex : "le RSI est à 65", "on teste le \
                  support à 64000", "le MACD croise à la hausse", structure de marché avec niveaux \
                  précis) ;
                - des fondamentaux quantifiés et actuels ayant un impact direct anticipé sur le prix \
                  (ex : flux ETF en dollars, taux directeurs, données on-chain chiffrées, funding rate, \
                  open interest).

                Le simple fait de commenter un mouvement de prix passé ou récent ("le token a pris X %%", \
                "le cours a chuté"), de mentionner l'intérêt d'institutions sans données chiffrées, ou de \
                poser une question rhétorique sur l'avenir d'un actif NE SUFFIT PAS : ce sont des accroches \
                narratives fréquentes même dans des vidéos qui ne sont pas des analyses de marché.

                Ne compte PAS comme analyse de marché, même si le sujet est crypto/blockchain et même si \
                l'introduction s'ouvre sur un cadrage "marché" (mouvement de prix, question sur l'avenir \
                d'un actif) :
                - un contenu historique ou biographique (ex : l'histoire d'un projet, d'un token, \
                  d'un fondateur, d'un exchange, son parcours réglementaire passé), même si ce cadrage \
                  narratif de départ évoque un mouvement de prix ou une question de marché avant de \
                  bifurquer vers le récit historique ;
                - une actualité/news sans avis de marché chiffré ni horizon donné ;
                - un tutoriel DeFi pas à pas ;
                - du contenu de psychologie/stratégie de trading générale sans analyse d'actif précis ;
                - une interview ou un portrait sans analyse de prix chiffrée.

                Exemple : une vidéo qui ouvre sur "le BNB a pris 11 %% alors qu'il devrait dévisser, \
                pourquoi ?" puis raconte l'histoire de CZ, de Binance et du token BNB (fondation, ICO, \
                parcours réglementaire européen) SANS revenir à des indicateurs ou données chiffrées \
                actuelles pour donner un avis directionnel, n'est PAS une analyse de marché — c'est un \
                contenu historique/biographique avec une accroche narrative orientée marché.

                Réponds STRICTEMENT au format JSON :
                {
                  "isMarketRelevant": true|false,
                  "category": "market_analysis|off_topic_history_or_background|off_topic_news|off_topic_defi_tutorial|off_topic_psychology|other"
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
