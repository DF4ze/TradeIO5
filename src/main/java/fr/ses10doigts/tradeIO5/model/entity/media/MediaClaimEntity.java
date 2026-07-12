package fr.ses10doigts.tradeIO5.model.entity.media;

import fr.ses10doigts.tradeIO5.model.enumerate.media.ClaimHorizon;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.SignalType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Une affirmation de marché extraite d'une {@link VideoContentEntity} (docs/etude-veille-media-youtube.md §3).
 * {@code sentiment} réutilise {@link SignalType} (BULLISH/BEARISH/NEUTRAL) plutôt qu'un nouvel enum.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "media_claims")
public class MediaClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "video_content_id", nullable = false)
    private VideoContentEntity videoContent;

    @Column(nullable = false)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SignalType sentiment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClaimHorizon horizon;

    private double confidence;

    /** Citation source, pour audit/traçabilité (Lot 4). */
    @Column(columnDefinition = "TEXT")
    private String excerpt;
}
