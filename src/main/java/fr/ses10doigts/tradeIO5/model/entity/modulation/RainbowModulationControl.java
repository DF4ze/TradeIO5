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
    private Long id;

    /** Marge de régulation, en % de la largeur de la bande (zone libre au centre). */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal regulationMarginPct;

    /** Accentuation maximale de la vente près de la borne haute (multiplicateur). */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal sellBoostMax;

    /** Borne absolue du biais achat/vente utilisateur (|biais| ≤ biasMax). */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal biasMax;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal powerMin;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal powerMax;

    /** Largeur minimale de la bande d'exposition utilisateur, en points de %. */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal minBandWidthPts;

    /** Plafond du produit des modulateurs appliqués à un montant. */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal modulationProductMax;

    @Column(nullable = false)
    private int autonomyDays;

    /** Plafonds de drawdown du wallet par profil de départ, en % négatifs (signalement du simulateur, aucun effet sur les ordres). */
    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapPrudentPct;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapBalancedPct;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapDynamicPct;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal drawdownCapFullPct;

    /** Taux de base par jour / cash alloué au lien, par actif calibré et profil (proposition indicative d'onboarding, aucun effet sur les ordres). */
    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateBtcPrudent;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateBtcBalanced;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateBtcDynamic;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateBtcFull;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateEthPrudent;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateEthBalanced;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateEthDynamic;

    @Column(nullable = false, precision = 12, scale = 8)
    private BigDecimal baseRateEthFull;

    /** Marge k du calage des taux, en information (non utilisée par le calcul). */
    @Column(name = "base_rate_margin_k", nullable = false, precision = 10, scale = 4)
    private BigDecimal baseRateMarginK;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 64)
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
                .baseRateBtcPrudent(def(ModulationDefaults.BASE_RATE_BTC_PRUDENT))
                .baseRateBtcBalanced(def(ModulationDefaults.BASE_RATE_BTC_BALANCED))
                .baseRateBtcDynamic(def(ModulationDefaults.BASE_RATE_BTC_DYNAMIC))
                .baseRateBtcFull(def(ModulationDefaults.BASE_RATE_BTC_FULL))
                .baseRateEthPrudent(def(ModulationDefaults.BASE_RATE_ETH_PRUDENT))
                .baseRateEthBalanced(def(ModulationDefaults.BASE_RATE_ETH_BALANCED))
                .baseRateEthDynamic(def(ModulationDefaults.BASE_RATE_ETH_DYNAMIC))
                .baseRateEthFull(def(ModulationDefaults.BASE_RATE_ETH_FULL))
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

    /** Taux de base retenu : valeur et indicateur « calibré » (faux = repli sur la ligne BTC). */
    public record BaseRate(BigDecimal value, boolean calibrated) {
    }

    /**
     * Taux de base du {@code profile} pour {@code asset} : ligne propre pour BTC / ETH, sinon repli sur la ligne BTC (non calibré).
     * {@code null} si le profil n'a pas de table ({@code CUSTOM}, nul).
     */
    public BaseRate baseRate(StartingProfile profile, String asset) {
        if (profile == null || !profile.selectable()) {
            return null;
        }
        boolean calibrated = ModulationDefaults.CALIBRATED_RATE_ASSETS.contains(asset);
        boolean eth = calibrated && "ETH".equals(asset);
        BigDecimal value = switch (profile) {
            case PRUDENT -> eth ? baseRateEthPrudent : baseRateBtcPrudent;
            case BALANCED -> eth ? baseRateEthBalanced : baseRateBtcBalanced;
            case DYNAMIC -> eth ? baseRateEthDynamic : baseRateBtcDynamic;
            default -> eth ? baseRateEthFull : baseRateBtcFull;
        };
        return new BaseRate(value, calibrated);
    }

    private static BigDecimal def(String name) {
        return ModulationDefaults.limit(name).defaultValue();
    }
}
