package fr.ses10doigts.tradeIO5.model.entity.execution;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionMode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Photo d'un plan d'ordres d'un utilisateur pour une passe : ce qu'on achèterait / vendrait, par quel chemin et à quel
 * coût. <b>Rien n'est envoyé</b>. Une révision par jeu d'entrées : un rejeu avec des entrées différentes marque l'ancienne
 * révision {@link PlanStatus#SUPERSEDED} (ligne d'audit) et en crée une nouvelle ; à entrées identiques rien ne change.
 */
@Entity
@Table(name = "rainbow_live_order_plan",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_order_plan_user_day_pass_rev",
                columnNames = {"user_id", "run_day", "pass_code", "revision"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveOrderPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Jour UTC de la passe. */
    @Column(name = "run_day", nullable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(name = "pass_code", nullable = false, length = 8)
    private RainbowLivePass pass;

    /** 1 pour le premier plan du (user, jour, passe), +1 à chaque remplacement. */
    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionMode mode;

    /** Coût (%) du chemin le plus cher parmi les actifs exécutables ; nul sans étape. */
    @Column(precision = 12, scale = 6)
    private BigDecimal totalCostPct;

    /** Pire niveau de Fee Test parmi les actifs exécutables ; nul sans étape. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeeTestLevel feeTestLevel;

    /** Raisons de blocage des actifs bloqués ({@code BTC:NO_PATH;PAXG:BELOW_MIN}), nul si aucun. */
    @Column(length = 255)
    private String blockReason;

    /** Hash des entrées (bindings + blocs live) : idempotence. */
    @Column(nullable = false, length = 64)
    private String inputsHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    /** Motif du blocage définitif décidé par l'exécuteur (plafond, non tradable...) ; nul sinon. */
    @Column(length = 120)
    private String executionBlockReason;

    @Column
    private Instant executionStartedAt;

    @Column
    private Instant executionFinishedAt;

    @ToString.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("priority ASC, assetSymbol ASC")
    private List<RainbowLiveOrderPlanAsset> assets = new ArrayList<>();

    @ToString.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("rank ASC")
    private List<RainbowLiveOrderStep> steps = new ArrayList<>();

    public boolean isExpiredAt(Instant now) {
        return status == PlanStatus.PLANNED && !now.isBefore(expiresAt);
    }

    /** Statut vu à l'instant {@code now} : {@code PLANNED} devient {@code EXPIRED} une fois {@code expiresAt} atteint. */
    public PlanStatus effectiveStatus(Instant now) {
        return isExpiredAt(now) ? PlanStatus.EXPIRED : status;
    }

    public void addAsset(RainbowLiveOrderPlanAsset asset) {
        asset.setPlan(this);
        assets.add(asset);
    }

    public void addStep(RainbowLiveOrderStep step) {
        step.setPlan(this);
        steps.add(step);
    }
}
