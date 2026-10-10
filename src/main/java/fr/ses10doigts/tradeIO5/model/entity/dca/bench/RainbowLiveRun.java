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
 * Run quotidien d'un preset : une ligne par (preset, jour UTC), deux blocs indépendants et nullables
 * (passe 23:55 = action fictive appliquée au wallet mock ; passe 00:05 = mêmes indicateurs sur la vraie
 * clôture + « action qui aurait été prise », jamais appliquée) + snapshot de la config effective.
 * Supprimé avec son preset.
 */
@Entity
@Table(name = "rainbow_live_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_run_preset_day", columnNames = {"preset_id", "run_day"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveRun {

    /** Tolérance de comparaison des montants/quantités entre les deux passes. */
    private static final double EPSILON = 1e-9;

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

    /** Passe 23:55 UTC (null tant que non jouée). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "close", column = @Column(name = "t2355_close")),
            @AttributeOverride(name = "sma", column = @Column(name = "t2355_sma")),
            @AttributeOverride(name = "atr", column = @Column(name = "t2355_atr")),
            @AttributeOverride(name = "boundDown2", column = @Column(name = "t2355_bound_down2")),
            @AttributeOverride(name = "boundDown1", column = @Column(name = "t2355_bound_down1")),
            @AttributeOverride(name = "boundUp1", column = @Column(name = "t2355_bound_up1")),
            @AttributeOverride(name = "boundUp2", column = @Column(name = "t2355_bound_up2")),
            @AttributeOverride(name = "boundUp3", column = @Column(name = "t2355_bound_up3")),
            @AttributeOverride(name = "zone", column = @Column(name = "t2355_zone")),
            @AttributeOverride(name = "athDistance", column = @Column(name = "t2355_ath_distance")),
            @AttributeOverride(name = "buyFactor", column = @Column(name = "t2355_buy_factor")),
            @AttributeOverride(name = "sellFactor", column = @Column(name = "t2355_sell_factor")),
            @AttributeOverride(name = "moonMode", column = @Column(name = "t2355_moon_mode")),
            @AttributeOverride(name = "buyArmed", column = @Column(name = "t2355_buy_armed")),
            @AttributeOverride(name = "sellArmed", column = @Column(name = "t2355_sell_armed")),
            @AttributeOverride(name = "buyLocked", column = @Column(name = "t2355_buy_locked")),
            @AttributeOverride(name = "cooldownRemaining", column = @Column(name = "t2355_cooldown_remaining")),
            @AttributeOverride(name = "moonReserveQty", column = @Column(name = "t2355_moon_reserve_qty")),
            @AttributeOverride(name = "activeSet", column = @Column(name = "t2355_active_set")),
            @AttributeOverride(name = "trendRegime", column = @Column(name = "t2355_trend_regime")),
            @AttributeOverride(name = "actionType", column = @Column(name = "t2355_action_type")),
            @AttributeOverride(name = "actionAmountUsdc", column = @Column(name = "t2355_action_amount_usdc")),
            @AttributeOverride(name = "actionQuantity", column = @Column(name = "t2355_action_quantity")),
            @AttributeOverride(name = "actionPrice", column = @Column(name = "t2355_action_price")),
            @AttributeOverride(name = "cashAfter", column = @Column(name = "t2355_cash_after")),
            @AttributeOverride(name = "positionAfter", column = @Column(name = "t2355_position_after")),
            @AttributeOverride(name = "liveStatus", column = @Column(name = "t2355_live_status")),
            @AttributeOverride(name = "liveWalletId", column = @Column(name = "t2355_live_wallet_id")),
            @AttributeOverride(name = "liveFetchedAt", column = @Column(name = "t2355_live_fetched_at")),
            @AttributeOverride(name = "liveCashUsdc", column = @Column(name = "t2355_live_cash_usdc")),
            @AttributeOverride(name = "livePositionQty", column = @Column(name = "t2355_live_position_qty")),
            @AttributeOverride(name = "liveTradableQty", column = @Column(name = "t2355_live_tradable_qty")),
            @AttributeOverride(name = "liveCashReserved", column = @Column(name = "t2355_live_cash_reserved")),
            @AttributeOverride(name = "liveBlockReason", column = @Column(name = "t2355_live_block_reason")),
            @AttributeOverride(name = "liveActionType", column = @Column(name = "t2355_live_action_type")),
            @AttributeOverride(name = "liveActionAmountUsdc", column = @Column(name = "t2355_live_action_amount_usdc")),
            @AttributeOverride(name = "liveActionQuantity", column = @Column(name = "t2355_live_action_quantity")),
            @AttributeOverride(name = "configHash", column = @Column(name = "t2355_config_hash")),
            @AttributeOverride(name = "computedAt", column = @Column(name = "t2355_computed_at"))
    })
    private RainbowLivePassBlock pass2355;

    /** Passe 00:05 UTC (null tant que non jouée). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "close", column = @Column(name = "t0005_close")),
            @AttributeOverride(name = "sma", column = @Column(name = "t0005_sma")),
            @AttributeOverride(name = "atr", column = @Column(name = "t0005_atr")),
            @AttributeOverride(name = "boundDown2", column = @Column(name = "t0005_bound_down2")),
            @AttributeOverride(name = "boundDown1", column = @Column(name = "t0005_bound_down1")),
            @AttributeOverride(name = "boundUp1", column = @Column(name = "t0005_bound_up1")),
            @AttributeOverride(name = "boundUp2", column = @Column(name = "t0005_bound_up2")),
            @AttributeOverride(name = "boundUp3", column = @Column(name = "t0005_bound_up3")),
            @AttributeOverride(name = "zone", column = @Column(name = "t0005_zone")),
            @AttributeOverride(name = "athDistance", column = @Column(name = "t0005_ath_distance")),
            @AttributeOverride(name = "buyFactor", column = @Column(name = "t0005_buy_factor")),
            @AttributeOverride(name = "sellFactor", column = @Column(name = "t0005_sell_factor")),
            @AttributeOverride(name = "moonMode", column = @Column(name = "t0005_moon_mode")),
            @AttributeOverride(name = "buyArmed", column = @Column(name = "t0005_buy_armed")),
            @AttributeOverride(name = "sellArmed", column = @Column(name = "t0005_sell_armed")),
            @AttributeOverride(name = "buyLocked", column = @Column(name = "t0005_buy_locked")),
            @AttributeOverride(name = "cooldownRemaining", column = @Column(name = "t0005_cooldown_remaining")),
            @AttributeOverride(name = "moonReserveQty", column = @Column(name = "t0005_moon_reserve_qty")),
            @AttributeOverride(name = "activeSet", column = @Column(name = "t0005_active_set")),
            @AttributeOverride(name = "trendRegime", column = @Column(name = "t0005_trend_regime")),
            @AttributeOverride(name = "actionType", column = @Column(name = "t0005_action_type")),
            @AttributeOverride(name = "actionAmountUsdc", column = @Column(name = "t0005_action_amount_usdc")),
            @AttributeOverride(name = "actionQuantity", column = @Column(name = "t0005_action_quantity")),
            @AttributeOverride(name = "actionPrice", column = @Column(name = "t0005_action_price")),
            @AttributeOverride(name = "cashAfter", column = @Column(name = "t0005_cash_after")),
            @AttributeOverride(name = "positionAfter", column = @Column(name = "t0005_position_after")),
            @AttributeOverride(name = "liveStatus", column = @Column(name = "t0005_live_status")),
            @AttributeOverride(name = "liveWalletId", column = @Column(name = "t0005_live_wallet_id")),
            @AttributeOverride(name = "liveFetchedAt", column = @Column(name = "t0005_live_fetched_at")),
            @AttributeOverride(name = "liveCashUsdc", column = @Column(name = "t0005_live_cash_usdc")),
            @AttributeOverride(name = "livePositionQty", column = @Column(name = "t0005_live_position_qty")),
            @AttributeOverride(name = "liveTradableQty", column = @Column(name = "t0005_live_tradable_qty")),
            @AttributeOverride(name = "liveCashReserved", column = @Column(name = "t0005_live_cash_reserved")),
            @AttributeOverride(name = "liveBlockReason", column = @Column(name = "t0005_live_block_reason")),
            @AttributeOverride(name = "liveActionType", column = @Column(name = "t0005_live_action_type")),
            @AttributeOverride(name = "liveActionAmountUsdc", column = @Column(name = "t0005_live_action_amount_usdc")),
            @AttributeOverride(name = "liveActionQuantity", column = @Column(name = "t0005_live_action_quantity")),
            @AttributeOverride(name = "configHash", column = @Column(name = "t0005_config_hash")),
            @AttributeOverride(name = "computedAt", column = @Column(name = "t0005_computed_at"))
    })
    private RainbowLivePassBlock pass0005;

    /** Snapshot de la config effective au dernier upsert d'une passe. */
    @Embedded
    private RainbowAtrConfig config;

    /** Hash du snapshot (détection « config modifiée » entre deux runs). */
    @Column(length = 32)
    private String configHash;

    /** Vrai si les deux passes sont jouées et que l'action 00:05 diffère de l'action 23:55 (type, montant ou quantité). */
    public boolean deltaActionDiffers() {
        if (pass2355 == null || pass0005 == null) {
            return false;
        }
        return pass2355.getActionType() != pass0005.getActionType()
                || differs(pass2355.getActionAmountUsdc(), pass0005.getActionAmountUsdc())
                || differs(pass2355.getActionQuantity(), pass0005.getActionQuantity());
    }

    private static boolean differs(Double a, Double b) {
        double x = a == null ? 0.0 : a;
        double y = b == null ? 0.0 : b;
        return Math.abs(x - y) > EPSILON;
    }
}
