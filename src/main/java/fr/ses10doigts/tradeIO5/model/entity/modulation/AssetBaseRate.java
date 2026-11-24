package fr.ses10doigts.tradeIO5.model.entity.modulation;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ligne de taux de base DÉDIÉE à un actif hors BTC / ETH (D1b, lot 2), écrite uniquement par la validation admin d'une proposition
 * ({@code NewAssetProposal}). Même unité que {@link RainbowModulationControl} : taux par jour et par unité de cash alloué au lien. Lue avant le repli
 * BTC « non calibré » ; aucun effet sur les ordres (proposition indicative d'onboarding).
 */
@Entity
@Table(name = "asset_base_rate", uniqueConstraints = @UniqueConstraint(name = "uk_asset_base_rate_asset", columnNames = "asset_symbol"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetBaseRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Column(name = "rate_prudent", nullable = false, precision = 12, scale = 8)
    private BigDecimal ratePrudent;

    @Column(name = "rate_balanced", nullable = false, precision = 12, scale = 8)
    private BigDecimal rateBalanced;

    @Column(name = "rate_dynamic", nullable = false, precision = 12, scale = 8)
    private BigDecimal rateDynamic;

    @Column(name = "rate_full", nullable = false, precision = 12, scale = 8)
    private BigDecimal rateFull;

    /** Proposition validée dont viennent les taux. */
    @Column(name = "proposal_id")
    private Long proposalId;

    @Column(name = "validated_by", nullable = false, length = 64)
    private String validatedBy;

    @Column(name = "validated_at", nullable = false)
    private Instant validatedAt;

    /** Taux du profil ; {@code null} pour un profil sans table ({@code CUSTOM}, nul). */
    public BigDecimal rate(StartingProfile profile) {
        if (profile == null || !profile.selectable()) {
            return null;
        }
        return switch (profile) {
            case PRUDENT -> ratePrudent;
            case BALANCED -> rateBalanced;
            case DYNAMIC -> rateDynamic;
            default -> rateFull;
        };
    }
}
