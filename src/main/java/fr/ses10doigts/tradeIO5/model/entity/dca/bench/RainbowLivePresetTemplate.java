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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Template de preset « système » du bench grandeur nature : réglages par défaut d'un (actif, nom), sans utilisateur,
 * sans wallet, sans run. Inséré uniquement par {@code RainbowLivePresetTemplateInitializer} (jamais écrasé ensuite) ;
 * chaque utilisateur en reçoit une copie à sa première utilisation ({@code RainbowLivePresetService#ensureSystemPresets}).
 * <p>
 * Immuable : pas de setter, et toute modification / suppression d'une ligne existante est refusée par les callbacks JPA.
 */
@Entity
@Table(name = "rainbow_live_preset_template",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_preset_template_asset_name",
                columnNames = {"asset_symbol", "name"}))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RainbowLivePresetTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbole nu de l'actif : BTC | ETH | PAXG. */
    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Nom du template (la copie user porte le préfixe système devant ce nom). */
    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RainbowLiveMode mode;

    private int analysisWindowMonths;

    private double initialCapitalUsdc;

    @Column(nullable = false)
    private Instant createdAt;

    /** Config du preset (FIXED : jeu propre ; TREND_MIX : instantané du jeu Bear, informatif comme sur le preset). */
    @Embedded
    private RainbowAtrConfig config;

    /** Réglages Trend Mix sérialisés ({@code TrendConfigDto} en JSON) ; null pour un template FIXED. */
    @Lob
    @Column(name = "trend_config_json")
    private String trendConfigJson;

    @PreUpdate
    @PreRemove
    void forbidChange() {
        throw new IllegalStateException("Template de preset système immuable (id=" + id + ", " + assetSymbol + " / " + name + ")");
    }
}
