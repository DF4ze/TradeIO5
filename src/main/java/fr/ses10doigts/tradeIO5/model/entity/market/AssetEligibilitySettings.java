package fr.ses10doigts.tradeIO5.model.entity.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Réglage admin unique de l'éligibilité (ligne d'id {@value #SINGLETON_ID}) : volume spot 24 h minimum, en USD. Créé par un initializer. */
@Entity
@Table(name = "asset_eligibility_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetEligibilitySettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "min_volume_usd", nullable = false, precision = 20, scale = 2)
    private BigDecimal minVolumeUsd;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
