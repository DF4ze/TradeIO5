package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;

/**
 * Run quotidien d'un preset : une ligne par (preset, jour UTC) portant le signal de la passe 23:55 UTC (bougie D1 du jour
 * encore ouverte) + snapshot de la config effective. Le run du jour suivant en rafraîchit les indicateurs avec la vraie
 * clôture ; l'action décidée à 23:55 reste figée. Supprimé avec son preset.
 */
@Entity
@Table(name = "rainbow_live_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_run_preset_day", columnNames = {"preset_id", "run_day"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "preset_id", nullable = false)
    private RainbowLivePreset preset;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Jour UTC de la bougie D1. */
    @Column(name = "run_day", nullable = false)
    private LocalDate day;

    /** Signal du jour (passe 23:55 UTC). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "close", column = @Column(name = "signal_close")),
            @AttributeOverride(name = "sma", column = @Column(name = "signal_sma")),
            @AttributeOverride(name = "atr", column = @Column(name = "signal_atr")),
            @AttributeOverride(name = "boundDown2", column = @Column(name = "signal_bound_down2")),
            @AttributeOverride(name = "boundDown1", column = @Column(name = "signal_bound_down1")),
            @AttributeOverride(name = "boundUp1", column = @Column(name = "signal_bound_up1")),
            @AttributeOverride(name = "boundUp2", column = @Column(name = "signal_bound_up2")),
            @AttributeOverride(name = "boundUp3", column = @Column(name = "signal_bound_up3")),
            @AttributeOverride(name = "zone", column = @Column(name = "signal_zone")),
            @AttributeOverride(name = "athDistance", column = @Column(name = "signal_ath_distance")),
            @AttributeOverride(name = "buyFactor", column = @Column(name = "signal_buy_factor")),
            @AttributeOverride(name = "sellFactor", column = @Column(name = "signal_sell_factor")),
            @AttributeOverride(name = "moonMode", column = @Column(name = "signal_moon_mode")),
            @AttributeOverride(name = "buyArmed", column = @Column(name = "signal_buy_armed")),
            @AttributeOverride(name = "sellArmed", column = @Column(name = "signal_sell_armed")),
            @AttributeOverride(name = "buyLocked", column = @Column(name = "signal_buy_locked")),
            @AttributeOverride(name = "cooldownRemaining", column = @Column(name = "signal_cooldown_remaining")),
            @AttributeOverride(name = "moonReserveQty", column = @Column(name = "signal_moon_reserve_qty")),
            @AttributeOverride(name = "activeSet", column = @Column(name = "signal_active_set")),
            @AttributeOverride(name = "trendRegime", column = @Column(name = "signal_trend_regime")),
            @AttributeOverride(name = "actionType", column = @Column(name = "signal_action_type")),
            @AttributeOverride(name = "actionAmountUsdc", column = @Column(name = "signal_action_amount_usdc")),
            @AttributeOverride(name = "actionQuantity", column = @Column(name = "signal_action_quantity")),
            @AttributeOverride(name = "actionPrice", column = @Column(name = "signal_action_price")),
            @AttributeOverride(name = "configHash", column = @Column(name = "signal_config_hash")),
            @AttributeOverride(name = "computedAt", column = @Column(name = "signal_computed_at"))
    })
    private RainbowLivePassBlock signal;

    /** Snapshot de la config effective au dernier upsert. */
    @Embedded
    private RainbowAtrConfig config;

    /** Hash du snapshot (détection « config modifiée » entre deux runs). */
    @Column(length = 32)
    private String configHash;
}
