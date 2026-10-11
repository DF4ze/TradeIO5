package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

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
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Run quotidien d'un LIEN (binding) : une ligne par (lien, jour UTC) portant les deux snapshots live indépendants et
 * nullables (passe 23:55, passe 00:05). Le signal moteur et le wallet fictif restent dans {@link RainbowLiveRun} (par
 * preset, partagé par tous les liens du preset) ; tout ce qui dépend du wallet réel est ici. Supprimé avec son lien.
 */
@Entity
@Table(name = "rainbow_live_binding_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_binding_run_binding_day", columnNames = {"binding_id", "run_day"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveBindingRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "binding_id", nullable = false)
    private RainbowLiveBinding binding;

    /** Jour UTC de la bougie D1. */
    @Column(name = "run_day", nullable = false)
    private LocalDate day;

    /** Snapshot de la passe 23:55 UTC (null tant que non jouée). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "t2355_status")),
            @AttributeOverride(name = "walletId", column = @Column(name = "t2355_wallet_id")),
            @AttributeOverride(name = "fetchedAt", column = @Column(name = "t2355_fetched_at")),
            @AttributeOverride(name = "cashUsd", column = @Column(name = "t2355_cash_usd")),
            @AttributeOverride(name = "cashDetail", column = @Column(name = "t2355_cash_detail")),
            @AttributeOverride(name = "positionQty", column = @Column(name = "t2355_position_qty")),
            @AttributeOverride(name = "tradableQty", column = @Column(name = "t2355_tradable_qty")),
            @AttributeOverride(name = "cashReserved", column = @Column(name = "t2355_cash_reserved")),
            @AttributeOverride(name = "blockReason", column = @Column(name = "t2355_block_reason")),
            @AttributeOverride(name = "actionType", column = @Column(name = "t2355_action_type")),
            @AttributeOverride(name = "actionAmountUsdc", column = @Column(name = "t2355_action_amount_usdc")),
            @AttributeOverride(name = "actionQuantity", column = @Column(name = "t2355_action_quantity"))
    })
    private RainbowLiveLiveBlock pass2355;

    /** Snapshot de la passe 00:05 UTC (null tant que non jouée). */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "status", column = @Column(name = "t0005_status")),
            @AttributeOverride(name = "walletId", column = @Column(name = "t0005_wallet_id")),
            @AttributeOverride(name = "fetchedAt", column = @Column(name = "t0005_fetched_at")),
            @AttributeOverride(name = "cashUsd", column = @Column(name = "t0005_cash_usd")),
            @AttributeOverride(name = "cashDetail", column = @Column(name = "t0005_cash_detail")),
            @AttributeOverride(name = "positionQty", column = @Column(name = "t0005_position_qty")),
            @AttributeOverride(name = "tradableQty", column = @Column(name = "t0005_tradable_qty")),
            @AttributeOverride(name = "cashReserved", column = @Column(name = "t0005_cash_reserved")),
            @AttributeOverride(name = "blockReason", column = @Column(name = "t0005_block_reason")),
            @AttributeOverride(name = "actionType", column = @Column(name = "t0005_action_type")),
            @AttributeOverride(name = "actionAmountUsdc", column = @Column(name = "t0005_action_amount_usdc")),
            @AttributeOverride(name = "actionQuantity", column = @Column(name = "t0005_action_quantity"))
    })
    private RainbowLiveLiveBlock pass0005;

    @Column(name = "computed_at")
    private Instant computedAt;
}
