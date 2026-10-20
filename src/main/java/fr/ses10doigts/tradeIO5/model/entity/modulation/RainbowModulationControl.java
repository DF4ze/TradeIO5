package fr.ses10doigts.tradeIO5.model.entity.modulation;

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
                .updatedAt(now).updatedBy(by).build();
    }

    private static BigDecimal def(String name) {
        return ModulationDefaults.limit(name).defaultValue();
    }
}
