package fr.ses10doigts.tradeIO5.model.entity.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

/**
 * Ligne du journal d'audit d'exécution : <b>append-only</b> (aucun setter, colonnes non modifiables, repository sans
 * suppression ni mise à jour). {@code payload} = couples clé=valeur (statut, quantités, prix, frais, codes), jamais un
 * secret ni une réponse brute de l'exchange.
 */
@Entity
@Table(name = "rainbow_live_order_event", indexes = @Index(name = "ix_rainbow_live_order_event_plan", columnList = "plan_id"))
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveOrderEvent {

    public static final int PAYLOAD_MAX = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    private RainbowLiveOrderPlan plan;

    @Column(name = "step_id", updatable = false)
    private Long stepId;

    @Column(name = "cl_ord_id", length = 32, updatable = false)
    private String clOrdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private OrderEventType type;

    @Column(length = PAYLOAD_MAX, updatable = false)
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
