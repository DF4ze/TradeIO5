package fr.ses10doigts.tradeIO5.model.entity.execution;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.math.BigDecimal;

/** Sort d'un actif dans un plan : action voulue, résultat (étapes ou blocage), coût et avertissement éventuel. */
@Entity
@Table(name = "rainbow_live_order_plan_asset")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveOrderPlanAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "plan_id", nullable = false)
    private RainbowLiveOrderPlan plan;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Ordre de passage du cash commun du binding. */
    @Column(nullable = false)
    private int priority;

    @Column(name = "wallet_id")
    private Long walletId;

    /** Action live voulue par le bloc de la passe (nulle sans snapshot). */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private RainbowLiveAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssetPlanOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PlanBlockReason blockReason;

    /** Coût (%) du chemin retenu (somme brute des jambes) ; nul sans chemin chiffré. */
    @Column(precision = 12, scale = 6)
    private BigDecimal costPct;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeeTestLevel feeTestLevel;

    /** Avertissement (Fee Test orange, cotation naturelle à la vente, estimation sur ticker...). */
    @Column(length = 255)
    private String warning;
}
