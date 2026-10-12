package fr.ses10doigts.tradeIO5.model.entity.execution;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Stock en transit : quantité d'un intermédiaire (BTC, ETH) acquise par la jambe 1 d'un achat {@code USDC -> BTC -> PAXG} dont
 * la jambe 2 a échoué après son retry borné. Aucun ordre compensatoire : le plan suivant termine d'abord la jambe 2 de ce stock
 * avant tout nouvel achat. Tant que {@code resolvedAt} est nul, le stock est actif : il compte comme source d'achat du wallet
 * (avec le cash USDC), et sa quantité est déduite de la position du lien portant la même devise sur ce wallet.
 */
@Entity
@Table(name = "transit_stock", indexes = @Index(name = "idx_transit_stock_wallet", columnList = "wallet_id, resolved_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    /** Devise détenue en transit (ex. BTC). */
    @Column(nullable = false, length = 16)
    private String currency;

    /** Quantité restante en transit, en unités de la devise. */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal quantity;

    /** Actif visé par la jambe 2 à terminer (ex. PAXG). */
    @Column(name = "target_asset", nullable = false, length = 16)
    private String targetAsset;

    /** Sens de la jambe 2 à terminer : {@code BUY} (achat de l'actif visé avec l'intermédiaire) ou {@code SELL} (vente de l'intermédiaire en stable) ; nul = {@code BUY}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "finish_side", length = 8)
    private StepSide finishSide;

    /** Plan et rang de l'étape d'origine (jambe 1 réussie). */
    @Column(name = "origin_plan_id")
    private Long originPlanId;

    @Column(name = "origin_step_rank")
    private Integer originStepRank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Date à laquelle la jambe 2 a terminé le stock ; nulle tant qu'il est actif. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
