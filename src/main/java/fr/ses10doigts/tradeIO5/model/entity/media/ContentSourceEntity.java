package fr.ses10doigts.tradeIO5.model.entity.media;

import fr.ses10doigts.tradeIO5.model.enumerate.media.ContentPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Config d'une chaîne/source suivie par la veille média (docs/etude-veille-media-youtube.md §3).
 * Ajouter une nouvelle chaîne = une nouvelle ligne, aucun code à toucher (Lot 4).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "content_sources",
        uniqueConstraints = @UniqueConstraint(name = "uk_content_source_channel_id", columnNames = "channelId"))
public class ContentSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContentPlatform platform;

    @Column(nullable = false, unique = true)
    private String channelId;

    private String displayName;

    /** Poids de crédibilité de la source, utilisé dans le calcul de poids des claims (Lot 3). */
    private double credibilityWeight;

    @Builder.Default
    private boolean active = true;
}
