package fr.ses10doigts.tradeIO5.model.entity.media;

import fr.ses10doigts.tradeIO5.model.enumerate.media.VideoContentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Une vidéo ingérée depuis une {@link ContentSourceEntity} (docs/etude-veille-media-youtube.md §3).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "video_contents",
        uniqueConstraints = @UniqueConstraint(name = "uk_video_content_source_video", columnNames = {"source_id", "videoId"}))
public class VideoContentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private ContentSourceEntity source;

    @Column(nullable = false)
    private String videoId;

    private String title;

    private Instant publishedAt;

    /**
     * Transcript sérialisé en JSON (liste de
     * {@code fr.ses10doigts.tradeIO5.service.tree.media.youtube.TranscriptSegment} — {text,
     * startSeconds, durationSeconds}), pas du texte brut concaténé : le Lot 2 (passe 1,
     * classification) a besoin de tronquer par fenêtre temporelle (segments dont
     * {@code startSeconds < 120}, docs/etude-veille-media-youtube.md §2/§6) exactement comme
     * {@code tools/media_watch/probe_transcript.py --excerpt-seconds}, ce qui suppose de conserver
     * les timestamps par segment plutôt qu'un simple texte joint. Le texte complet (passe 2) se
     * reconstruit en joignant le texte de tous les segments.
     */
    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoContentStatus status;

    /**
     * Renseigné à chaque cas d'erreur identifié (pas de transcript disponible, RSS indisponible,
     * JSON LLM invalide, timeout...) — cf. Lot 4, observabilité. Nullable : vide tant qu'aucune
     * erreur ne s'est produite.
     */
    private String errorReason;
}
