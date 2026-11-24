package fr.ses10doigts.tradeIO5.model.entity.modulation;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
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
import java.util.EnumMap;
import java.util.Map;
import java.time.Instant;

/**
 * Paramètres <b>globaux</b> de la modulation Rainbow (une seule ligne, id {@value #SINGLETON_ID}), réglables par l'admin
 * dans les bornes absolues de {@link ModulationDefaults}, lus sans cache. Ligne absente => défauts en constantes.
 */
@Entity
@Table(name = "rainbow_modulation_control")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowModulationControl {

    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    /** Marge de régulation, en % de la largeur de la bande (zone libre au centre). */
    @Column(name = "regulation_margin_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal regulationMarginPct;

    /** Accentuation maximale de la vente près de la borne haute (multiplicateur). */
    @Column(name = "sell_boost_max", nullable = false, precision = 10, scale = 4)
    private BigDecimal sellBoostMax;

    /** Borne absolue du biais achat/vente utilisateur (|biais| ≤ biasMax). */
    @Column(name = "bias_max", nullable = false, precision = 10, scale = 4)
    private BigDecimal biasMax;

    @Column(name = "power_min", nullable = false, precision = 10, scale = 4)
    private BigDecimal powerMin;

    @Column(name = "power_max", nullable = false, precision = 10, scale = 4)
    private BigDecimal powerMax;

    /** Largeur minimale de la bande d'exposition utilisateur, en points de %. */
    @Column(name = "min_band_width_pts", nullable = false, precision = 10, scale = 4)
    private BigDecimal minBandWidthPts;

    /** Plafond du produit des modulateurs appliqués à un montant. */
    @Column(name = "modulation_product_max", nullable = false, precision = 10, scale = 4)
    private BigDecimal modulationProductMax;

    @Column(name = "autonomy_days", nullable = false)
    private int autonomyDays;

    /** Plafonds de drawdown du wallet par profil de départ, en % négatifs (signalement du simulateur, aucun effet sur les ordres). */
    @Column(name = "drawdown_cap_prudent_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapPrudentPct;

    @Column(name = "drawdown_cap_balanced_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapBalancedPct;

    @Column(name = "drawdown_cap_dynamic_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapDynamicPct;

    @Column(name = "drawdown_cap_full_pct", nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapFullPct;

    /** Marge k du calage des taux, en information (non utilisée par le calcul). Les taux eux-mêmes vivent dans {@code asset_base_rate}. */
    @Column(name = "base_rate_margin_k", nullable = false, precision = 10, scale = 4)
    private BigDecimal baseRateMarginK;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    /** Ligne aux défauts en constantes. */
    public static RainbowModulationControl defaults(Instant now, String by) {
        return RainbowModulationControl.builder().id(SINGLETON_ID)
                .regulationMarginPct(def(ModulationDefaults.REGULATION_MARGIN_PCT))
                .sellBoostMax(def(ModulationDefaults.SELL_BOOST_MAX))
                .biasMax(def(ModulationDefaults.BIAS_MAX))
                .powerMin(def(ModulationDefaults.POWER_MIN))
                .powerMax(def(ModulationDefaults.POWER_MAX))
                .minBandWidthPts(def(ModulationDefaults.MIN_BAND_WIDTH_PTS))
                .modulationProductMax(def(ModulationDefaults.MODULATION_PRODUCT_MAX))
                .autonomyDays(def(ModulationDefaults.AUTONOMY_DAYS).intValueExact())
                .drawdownCapPrudentPct(def(ModulationDefaults.DRAWDOWN_CAP_PRUDENT_PCT))
                .drawdownCapBalancedPct(def(ModulationDefaults.DRAWDOWN_CAP_BALANCED_PCT))
                .drawdownCapDynamicPct(def(ModulationDefaults.DRAWDOWN_CAP_DYNAMIC_PCT))
                .drawdownCapFullPct(def(ModulationDefaults.DRAWDOWN_CAP_FULL_PCT))
                .baseRateMarginK(def(ModulationDefaults.BASE_RATE_MARGIN_K))
                .updatedAt(now).updatedBy(by).build();
    }

    /** Plafonds de drawdown (en % négatifs) des 4 profils choisissables, dans l'ordre Prudent → Full ; {@code CUSTOM} n'en a pas. */
    public Map<StartingProfile, BigDecimal> drawdownCaps() {
        Map<StartingProfile, BigDecimal> caps = new EnumMap<>(StartingProfile.class);
        caps.put(StartingProfile.PRUDENT, drawdownCapPrudentPct);
        caps.put(StartingProfile.BALANCED, drawdownCapBalancedPct);
        caps.put(StartingProfile.DYNAMIC, drawdownCapDynamicPct);
        caps.put(StartingProfile.FULL, drawdownCapFullPct);
        return caps;
    }

    private static BigDecimal def(String name) {
        return ModulationDefaults.limit(name).defaultValue();
    }
}
