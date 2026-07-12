package fr.ses10doigts.tradeIO5.service.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import fr.ses10doigts.tradeIO5.repository.ContentSourceRepository;
import fr.ses10doigts.tradeIO5.repository.VideoContentRepository;
import fr.ses10doigts.tradeIO5.service.tree.media.MediaCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeRssClient;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeTranscriptClient;
import fr.ses10doigts.tradeIO5.service.tree.media.youtube.YoutubeVideoRef;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Premier job planifié du projet (package {@code service/scheduler/} vide jusqu'ici) — poll
 * périodique de chaque {@link ContentSourceEntity} active pour découvrir de nouvelles vidéos,
 * récupérer leur transcript, et les écrire en statut {@code PENDING}
 * (docs/etude-veille-media-youtube.md §4, Lot 1 ; docs/prompt-implementation-veille-media-full.md,
 * Lot 1b). {@code @EnableScheduling} déjà présent sur {@code TradeIO5} (classe principale).
 * <p>
 * Cadence par défaut : toutes les 6h — la cadence de publication observée (~4 vidéos/semaine chez
 * Cryptolyze) ne justifie pas un polling agressif.
 */
@Component
@RequiredArgsConstructor
public class MediaWatchIngestionJob {

    private static final String NO_TRANSCRIPT_ERROR = "no_transcript_available";

    private final Logger logger = LoggerFactory.getLogger(MediaWatchIngestionJob.class);

    private final ContentSourceRepository contentSourceRepository;
    private final VideoContentRepository videoContentRepository;
    private final YoutubeRssClient rssClient;
    private final YoutubeTranscriptClient transcriptClient;
    private final MediaCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Scheduled(cron = "${tradeio.media-watch.poll-cron:0 0 */6 * * *}")
    public void pollActiveSources() {
        List<ContentSourceEntity> sources = contentSourceRepository.findByActiveTrue();

        ApiCredentialDTO credential = credentialResolver.resolve(WebProviderCode.YOUTUBE);
        if (credential == null) {
            logger.warn("MediaWatchIngestionJob: aucune credential YOUTUBE résolue, exécution ignorée.");
            return;
        }

        int discovered = 0;
        int alreadyKnown = 0;
        int errorSources = 0;

        for (ContentSourceEntity source : sources) {
            // Isolation par source (même principe que ForexFactoryCalendarClient, dégradation
            // gracieuse) : une source en échec (RSS injoignable, etc.) ne doit jamais empêcher le
            // traitement des autres.
            try {
                int[] counts = ingestSource(source, credential);
                discovered += counts[0];
                alreadyKnown += counts[1];
            } catch (Exception e) {
                errorSources++;
                logger.error("MediaWatchIngestionJob: échec de traitement de la source '{}' ({})",
                        source.getDisplayName(), source.getChannelId(), e);
            }
        }

        logger.info("MediaWatchIngestionJob: exécution terminée — {} nouvelle(s) vidéo(s), "
                        + "{} déjà connue(s), {} source(s) en erreur (sur {} source(s) active(s)).",
                discovered, alreadyKnown, errorSources, sources.size());
    }

    private int[] ingestSource(ContentSourceEntity source, ApiCredentialDTO credential) {
        List<YoutubeVideoRef> latestVideos = rssClient.fetchLatestVideos(credential, source.getChannelId());

        int discovered = 0;
        int alreadyKnown = 0;

        for (YoutubeVideoRef ref : latestVideos) {
            // Idempotence : ne jamais retraiter une vidéo déjà connue.
            if (videoContentRepository.existsBySourceAndVideoId(source, ref.videoId())) {
                alreadyKnown++;
                continue;
            }

            discovered++;
            ingestVideo(source, ref, credential);
        }

        return new int[]{discovered, alreadyKnown};
    }

    private void ingestVideo(ContentSourceEntity source, YoutubeVideoRef ref, ApiCredentialDTO credential) {
        VideoContentEntity.VideoContentEntityBuilder builder = VideoContentEntity.builder()
                .source(source)
                .videoId(ref.videoId())
                .title(ref.title())
                .publishedAt(ref.publishedAt());

        Optional<List<TranscriptSegment>> transcript = transcriptClient.fetchTranscript(credential, ref.videoId());

        if (transcript.isEmpty()) {
            videoContentRepository.save(builder
                    .status(VideoContentStatus.ERROR)
                    .errorReason(NO_TRANSCRIPT_ERROR)
                    .build());
            return;
        }

        Optional<String> serialized = serialize(transcript.get());
        if (serialized.isEmpty()) {
            // Anomalie de sérialisation improbable mais possible : on isole cette vidéo en erreur
            // plutôt que de faire échouer tout le job (même principe d'isolation qu'au niveau source).
            videoContentRepository.save(builder
                    .status(VideoContentStatus.ERROR)
                    .errorReason("transcript_serialization_failed")
                    .build());
            return;
        }

        videoContentRepository.save(builder
                .transcript(serialized.get())
                .status(VideoContentStatus.PENDING)
                .build());
    }

    private Optional<String> serialize(List<TranscriptSegment> segments) {
        try {
            return Optional.of(objectMapper.writeValueAsString(segments));
        } catch (JsonProcessingException e) {
            logger.error("MediaWatchIngestionJob: échec de sérialisation du transcript", e);
            return Optional.empty();
        }
    }
}
