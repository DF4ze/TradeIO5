package fr.ses10doigts.tradeIO5.service.tree.media;

import fr.ses10doigts.tradeIO5.model.dto.tree.media.ClassificationResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractedClaim;
import fr.ses10doigts.tradeIO5.model.dto.tree.media.ExtractionResult;
import fr.ses10doigts.tradeIO5.model.entity.media.MediaClaimEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import fr.ses10doigts.tradeIO5.exceptions.LlmResponseParsingException;
import fr.ses10doigts.tradeIO5.repository.MediaClaimRepository;
import fr.ses10doigts.tradeIO5.repository.VideoContentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrateur des 2 passes du pipeline de veille média
 * (docs/prompt-implementation-veille-media-full.md, Lot 2) : consomme les
 * {@code VideoContentEntity} en statut {@code PENDING}, applique la passe 1 (classification), puis
 * la passe 2 (extraction) uniquement si pertinent, et persiste les {@code MediaClaimEntity}.
 * <p>
 * Isolation d'erreur à deux niveaux : une vidéo en échec (timeout LLM, JSON invalide) ne bloque
 * jamais le traitement des autres vidéos de la même exécution ; un claim individuel avec un
 * sentiment/horizon invalide ne bloque jamais la persistance des autres claims de la même vidéo.
 */
@Service
public class TranscriptExtractionService {

    private final Logger logger = LoggerFactory.getLogger(TranscriptExtractionService.class);

    private final VideoContentRepository videoContentRepository;
    private final MediaClaimRepository mediaClaimRepository;
    private final TranscriptClassificationService classificationService;
    private final TranscriptClaimExtractionService claimExtractionService;

    public TranscriptExtractionService(VideoContentRepository videoContentRepository,
                                        MediaClaimRepository mediaClaimRepository,
                                        TranscriptClassificationService classificationService,
                                        TranscriptClaimExtractionService claimExtractionService) {
        this.videoContentRepository = videoContentRepository;
        this.mediaClaimRepository = mediaClaimRepository;
        this.classificationService = classificationService;
        this.claimExtractionService = claimExtractionService;
    }

    public void processPendingVideos() {
        List<VideoContentEntity> pending = videoContentRepository.findByStatus(VideoContentStatus.PENDING);

        int processed = 0;
        int irrelevant = 0;
        int errors = 0;

        for (VideoContentEntity video : pending) {
            try {
                VideoContentStatus resultStatus = processVideo(video);
                if (resultStatus == VideoContentStatus.PROCESSED) {
                    processed++;
                } else if (resultStatus == VideoContentStatus.IRRELEVANT) {
                    irrelevant++;
                }
            } catch (Exception e) {
                errors++;
                video.setStatus(VideoContentStatus.ERROR);
                video.setErrorReason(describeError(e));
                videoContentRepository.save(video);
                logger.error("TranscriptExtractionService: échec de traitement de la vidéo '{}' ({})",
                        video.getTitle(), video.getVideoId(), e);
            }
        }

        logger.info("TranscriptExtractionService: exécution terminée — {} traitée(s), {} hors-sujet, "
                        + "{} erreur(s) (sur {} vidéo(s) en attente).",
                processed, irrelevant, errors, pending.size());
    }

    private VideoContentStatus processVideo(VideoContentEntity video) {
        ClassificationResult classification = classificationService.classify(video);

        if (!classification.isMarketRelevant()) {
            video.setStatus(VideoContentStatus.IRRELEVANT);
            videoContentRepository.save(video);
            return VideoContentStatus.IRRELEVANT;
        }

        ExtractionResult extraction = claimExtractionService.extractClaims(video);
        List<MediaClaimEntity> claims = toEntities(video, extraction);

        if (!claims.isEmpty()) {
            mediaClaimRepository.saveAll(claims);
        }

        video.setStatus(VideoContentStatus.PROCESSED);
        videoContentRepository.save(video);
        return VideoContentStatus.PROCESSED;
    }

    private List<MediaClaimEntity> toEntities(VideoContentEntity video, ExtractionResult extraction) {
        if (extraction == null || extraction.claims() == null) {
            return List.of();
        }

        List<MediaClaimEntity> entities = new ArrayList<>();

        for (ExtractedClaim claim : extraction.claims()) {
            try {
                entities.add(MediaClaimEntity.builder()
                        .videoContent(video)
                        .symbol(claim.symbol())
                        .sentiment(TranscriptClaimExtractionService.parseSentiment(claim.sentiment()))
                        .horizon(TranscriptClaimExtractionService.parseHorizon(claim.horizon()))
                        .confidence(claim.confidence())
                        .excerpt(claim.excerpt())
                        .build());
            } catch (IllegalArgumentException | NullPointerException e) {
                // sentiment/horizon invalide (valueOf) sur CE claim précis uniquement : on
                // l'ignore plutôt que de faire échouer toute la liste de claims de la vidéo.
                logger.warn("TranscriptExtractionService: claim ignoré (sentiment/horizon invalide) pour la vidéo '{}' : {}",
                        video.getVideoId(), e.getMessage());
            }
        }

        return entities;
    }

    /**
     * Message d'erreur explicite plutôt que générique (Lot 4, observabilité) : distingue
     * spécifiquement un échec côté LLM (JSON invalide, réponse vide/timeout — les deux remontent
     * via {@link LlmResponseParsingException}, cf. {@code OpenAIService#ask(..., Class)}) d'une
     * autre erreur de traitement.
     */
    private String describeError(Exception e) {
        if (e instanceof LlmResponseParsingException) {
            return "llm_response_invalid: " + e.getMessage();
        }
        return "processing_error (" + e.getClass().getSimpleName() + ")"
                + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }
}
