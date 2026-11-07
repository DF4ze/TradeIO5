package fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Ancien preset utilisateur (table {@code rainbow_live_preset}), conservé en LECTURE SEULE pour la migration vers
 * {@code TrendPreset} / {@code RainbowPreset} / {@code AssetAffectation} ({@code AffectationMigrationRunner}). Plus aucune
 * écriture ; à supprimer avec sa table (cf. {@code docs/known-gaps/nettoyages.md}).
 */
@Entity
@Table(name = "rainbow_live_preset")
@Getter
@NoArgsConstructor
public class LegacyRainbowPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Column(nullable = false, length = 64)
    private String name;

    private boolean enabled;

    /** Id de l'ancienne stratégie Actif suivie (table {@code rainbow_asset_strategy}) ; nul = preset propre à l'utilisateur. */
    @Column(name = "asset_strategy_id")
    private Long assetStrategyId;

    /** {@code TREND_MIX} ou nul / autre (jeu propre sans Trend). */
    @Column(length = 16)
    private String mode;

    private int analysisWindowMonths;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Embedded
    private RainbowAtrConfig config;
}
