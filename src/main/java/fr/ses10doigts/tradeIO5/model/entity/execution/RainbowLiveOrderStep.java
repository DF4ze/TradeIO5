package fr.ses10doigts.tradeIO5.model.entity.execution;

import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
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
import java.time.Instant;

/**
 * Un ordre limit IOC plafonné, calculé par le plan puis (si le mode LIVE est déverrouillé) envoyé par l'exécuteur ;
 * les colonnes d'exécution restent nulles tant que l'étape n'a pas été envoyée. {@code rank} = rang dans le chemin de l'actif
 * (1 = pont éventuel, puis jambe d'achat). {@code px} = prix plafond (prix de référence ± tolérance de slippage, arrondi
 * au tick) ; {@code sz} en unités de la base ; {@code quoteAmount} en monnaie de cotation.
 */
@Entity
@Table(name = "rainbow_live_order_step")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveOrderStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "plan_id", nullable = false)
    private RainbowLiveOrderPlan plan;

    @Column(name = "step_rank", nullable = false)
    private int rank;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Column(name = "inst_id", nullable = false, length = 32)
    private String instId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private StepSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StepOrderType ordType;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal sz;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal px;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal quoteAmount;

    /** Identifiant client déterministe (alphanumérique, 32 caractères maximum). */
    @Column(nullable = false, length = 32)
    private String clOrdId;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal feePct;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal spreadPct;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal slippagePct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StepStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PathEstimation estimation;

    // ---- exécution (nulles tant que l'étape n'est pas envoyée)

    /** Identifiant d'ordre de l'exchange. */
    @Column(length = 64)
    private String ordId;

    /** Taille réellement envoyée (peut être inférieure à {@code sz} quand l'étape précédente a livré moins que prévu). */
    @Column(precision = 38, scale = 18)
    private BigDecimal sentSz;

    @Column(precision = 38, scale = 18)
    private BigDecimal filledSz;

    @Column(precision = 38, scale = 18)
    private BigDecimal avgFillPx;

    /** Frais réels prélevés (coût positif) et sa devise. */
    @Column(precision = 38, scale = 18)
    private BigDecimal feeAmount;

    @Column(length = 20)
    private String feeCurrency;

    /** Quantité nette reçue (base à l'achat, cotation à la vente), frais déduits, et sa devise. */
    @Column(precision = 38, scale = 18)
    private BigDecimal receivedAmount;

    @Column(length = 20)
    private String receivedCurrency;

    /** Mid du carnet au re-devis précédant l'envoi, et slippage réel (%) = prix moyen de remplissage vs ce mid. */
    @Column(precision = 38, scale = 18)
    private BigDecimal quoteMid;

    @Column(precision = 12, scale = 6)
    private BigDecimal realSlippagePct;

    @Column
    private Instant submittedAt;

    @Column
    private Instant finishedAt;

    /** Code court de la dernière erreur (code OKX, motif d'arrêt) ; jamais de message d'exchange brut. */
    @Column(length = 64)
    private String lastError;
}
