package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PreRemove;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Stratégie Actif pilotée par le compte System (ex. « TrendMix Rainbow DCA ATR · BTC ») : réglages par défaut d'un
 * (actif, nom), sans utilisateur, wallet ni run. Insérée par {@code RainbowAssetStrategyInitializer} si absente (jamais
 * écrasée), puis modifiée en base par System ({@code revision} incrémentée à chaque modification). Les presets des
 * users qui la suivent lisent sa config à chaque passe (aucune copie) ; la dupliquer détache le preset.
 * Non supprimable.
 */
@Entity
@Table(name = "rainbow_asset_strategy",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_asset_strategy_asset_name",
                columnNames = {"asset_symbol", "name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RainbowAssetStrategy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbole nu de l'actif : BTC | ETH | PAXG. */
    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Nom de la stratégie (le preset user qui la suit porte le préfixe système devant ce nom). */
    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RainbowLiveMode mode;

    private int analysisWindowMonths;

    private double initialCapitalUsdc;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Révision de la config : 1 à la création, +1 à chaque modification par System. */
    private int revision;

    /** Config du preset (FIXED : jeu propre ; TREND_MIX : instantané du jeu Bear, informatif comme sur le preset). */
    @Embedded
    private RainbowAtrConfig config;

    /** Réglages Trend Mix sérialisés ({@code TrendConfigDto} en JSON) ; null pour une stratégie FIXED. */
    @Lob
    @Column(name = "trend_config_json", columnDefinition = "longtext")
    private String trendConfigJson;

    @PreRemove
    void forbidRemove() {
        throw new IllegalStateException("Stratégie Actif non supprimable (id=" + id + ", " + assetSymbol + " / " + name + ")");
    }
}
