package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity;
import fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoContentRepository extends JpaRepository<VideoContentEntity, Long> {

    /** Idempotence du job d'ingestion (Lot 1b) : ne jamais retraiter une vidéo déjà connue. */
    boolean existsBySourceAndVideoId(ContentSourceEntity source, String videoId);

    /** Consommation par TranscriptExtractionService (Lot 2). */
    List<VideoContentEntity> findByStatus(VideoContentStatus status);
}
