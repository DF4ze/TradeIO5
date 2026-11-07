package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Réglages Trend d'un actif (fenêtres de régression, seuils, confirmation, SMA / ATR, mèches, mapping du RANGE).
 * Le Trend final ne renvoie que UP / DOWN ; {@code rangeMapping} est un réglage interne. Origine {@code SYSTEM}
 * (propriétaire nul, rôle de base {@link BasePresetRole#TREND} unique par actif) ou {@code USER}.
 */
@Entity
@Table(name = "trend_preset",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_trend_preset_owner_asset_name", columnNames = {"owner_id", "asset_symbol", "name"}),
                @UniqueConstraint(name = "uk_trend_preset_asset_base_role", columnNames = {"asset_symbol", "base_role"})})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendPreset implements ExpertPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbole nu de l'actif : BTC | ETH | PAXG. */
    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Provider de référence (données de calibrage) ; nul = non précisé. */
    @Column(name = "reference_provider", length = 32)
    private String referenceProvider;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PresetOrigin origin;

    /** Propriétaire ; nul pour un preset SYSTEM. */
    @ManyToOne
    @JoinColumn(name = "owner_id")
    private User owner;

    /** Rôle de base d'un preset SYSTEM semé ; nul pour les autres. */
    @Enumerated(EnumType.STRING)
    @Column(name = "base_role", length = 16)
    private BasePresetRole baseRole;

    private int revision;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private int shortWindow;
    private int mediumWindow;
    private int longWindow;
    private double slopeScale;
    private double enterThreshold;
    private double exitThreshold;
    private int confirmDays;
    private int smaPeriod;
    private int atrPeriod;
    private double atrMultiplier;
    private boolean wickDown;
    private boolean wickUp;

    /** {@code RainbowSetSelector.RangeMapping}. */
    @Column(nullable = false, length = 16)
    private String rangeMapping;

    public TrendMixCalculator.Params toParams() {
        return new TrendMixCalculator.Params(shortWindow, mediumWindow, longWindow, slopeScale, enterThreshold,
                exitThreshold, confirmDays, smaPeriod, atrPeriod, atrMultiplier, wickDown, wickUp);
    }

    public void applyParams(TrendMixCalculator.Params p) {
        shortWindow = p.shortWindow();
        mediumWindow = p.mediumWindow();
        longWindow = p.longWindow();
        slopeScale = p.slopeScale();
        enterThreshold = p.enter();
        exitThreshold = p.exit();
        confirmDays = p.confirm();
        smaPeriod = p.smaPeriod();
        atrPeriod = p.atrPeriod();
        atrMultiplier = p.atrMultiplier();
        wickDown = p.wickDown();
        wickUp = p.wickUp();
    }
}
