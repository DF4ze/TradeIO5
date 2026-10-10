package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
 * Preset du bench grandeur nature Rainbow DCA ATR : un jeu de paramètres pour (utilisateur, actif).
 * Timeframe 1D fixe. Le capital initial du wallet mock est figé à la création ; l'édition d'un preset
 * conserve identité, wallet et historique.
 */
@Entity
@Table(name = "rainbow_live_preset",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_preset_user_asset_name",
                columnNames = {"user_id", "asset_symbol", "name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLivePreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Symbole nu de l'actif : BTC | ETH | PAXG. */
    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Column(nullable = false, length = 64)
    private String name;

    private boolean enabled;

    /**
     * Stratégie Actif suivie : config, fenêtre et réglages Trend viennent de la stratégie à chaque passe (ni modifiable
     * ni supprimable, seul {@code enabled} change). {@code null} = preset propre à l'utilisateur.
     */
    @ManyToOne
    @JoinColumn(name = "asset_strategy_id")
    private RainbowAssetStrategy assetStrategy;

    /** Mode de calcul ; {@code null} (lignes historiques) = {@link RainbowLiveMode#FIXED}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RainbowLiveMode mode;

    /** Fenêtre rejouée par le moteur, en mois. */
    private int analysisWindowMonths;

    /** Capital initial (USDC) du wallet mock. */
    private double initialCapitalUsdc;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Paramètres Tuning + Globals (dont {@code baseAmount}). */
    @Embedded
    private RainbowAtrConfig config;

    public boolean isFollowingStrategy() {
        return assetStrategy != null;
    }

    public boolean isTrendMix() {
        return mode == RainbowLiveMode.TREND_MIX;
    }
}
