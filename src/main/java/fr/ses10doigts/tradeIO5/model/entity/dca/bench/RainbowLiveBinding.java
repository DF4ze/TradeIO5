package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lien (utilisateur, actif, wallet) -> Affectation de l'actif + wallet réel (lecture seule). Changer de presets = modifier
 * l'Affectation (le lien suit). Donnée utilisateur : créée par l'API, jamais par un initializer.
 */
@Entity
@Table(name = "rainbow_live_binding",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_binding_user_asset_wallet",
                columnNames = {"user_id", "asset_symbol", "wallet_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Symbole nu de l'actif : BTC | ETH | PAXG. */
    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Affectation (utilisateur, actif) jouée en live pour ce lien. */
    @ManyToOne
    @JoinColumn(name = "affectation_id")
    private AssetAffectation affectation;

    /** Wallet réel lu (exchange + credential). */
    @ToString.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /** Part (0-100 %) de la position réelle offerte à la stratégie, dynamique (suit le wallet). */
    @Column(nullable = false)
    private double bagPercent;

    /**
     * Montant de base (USDC) propre au lien, choisi par l'utilisateur ; nul => celui du preset. Met à l'échelle les achats
     * (proportionnels au montant de base), jamais les ventes.
     */
    @Column(name = "base_amount")
    private Double baseAmount;

    /** Ordre de passage du cash commun (plus petit = servi en premier). */
    @Column(nullable = false)
    private int priority;

    /** Dernier statut de tradabilité de (actif, exchange) ; nul tant que le binding n'a jamais été vérifié. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private BindingCheckStatus tradability;

    /** Cotation de la dernière jambe d'achat de l'actif (USDC ou intermédiaire), et route mémorisée (ex. {@code USDC>BTC>PAXG}). */
    @Column(name = "quote_member", length = 16)
    private String quoteMember;

    @Column(name = "route_key", length = 64)
    private String routeKey;

    /**
     * Route d'achat imposée par l'utilisateur (ex. {@code USDC>ETH>BTC}), nulle = la moins chère calculée par
     * {@code PathFinder}. Si elle n'est plus disponible, l'achat est bloqué (jamais de route de remplacement silencieuse).
     */
    @Column(name = "preferred_route", length = 64)
    private String preferredRoute;

    /**
     * Dernier Fee Test réalisé pour ce lien (route chiffrée, coût total en %, niveau, montant d'achat coté en USD, date) :
     * posé à la création du lien et à chaque « Recalculer ». Nul tant qu'aucun devis n'a abouti.
     */
    @Column(name = "fee_test_route", length = 64)
    private String feeTestRoute;

    @Column(name = "fee_test_pct", precision = 12, scale = 6)
    private BigDecimal feeTestPct;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_test_level", length = 16)
    private FeeTestLevel feeTestLevel;

    @Column(name = "fee_test_amount", precision = 24, scale = 8)
    private BigDecimal feeTestAmount;

    @Column(name = "fee_test_at")
    private Instant feeTestAt;

    /** Mémorise un devis chiffré (ignoré sans chemin : {@code NO_PATH} garde le dernier Fee Test connu). */
    public void recordFeeTest(PathQuote quote, BigDecimal amount, Instant at) {
        if (quote == null || quote.status() == PathStatus.NO_PATH || quote.totalCostPct() == null) {
            return;
        }
        feeTestRoute = quote.routeKey();
        feeTestPct = quote.totalCostPct();
        feeTestLevel = quote.feeTestLevel();
        feeTestAmount = amount;
        feeTestAt = at;
    }

    @Column(name = "tradability_checked_at")
    private Instant tradabilityCheckedAt;

    /**
     * Interrupteur d'exécution du binding (défaut faux). Faux => son plan d'ordres est marqué {@code DISABLED} (visible,
     * sans étape) et rien n'est envoyé. L'utilisateur active son propre binding ; l'admin arme le 1ᵉʳ ordre réel.
     */
    @Column(name = "execution_enabled", nullable = false)
    private boolean executionEnabled;

    /**
     * Mode simulation (défaut faux) : lien non exécuté mais dont toute la chaîne (signal, dimensionnement, plan d'ordres chiffré)
     * est jouée et taguée « simulation ». Exclusif de {@link #executionEnabled} (voir {@link #executionMode()}).
     */
    @Column(name = "simulation", nullable = false)
    private boolean simulation;

    /**
     * Achat minimum (USD) choisi par l'utilisateur ; nul = aucun minimum propre. Un achat (mise du jour + carry) sous ce
     * minimum n'est pas passé : il est reporté (carry) jusqu'à atteindre le minimum.
     */
    @Column(name = "min_buy_usd")
    private Double minBuyUsd;

    /** Mode d'exécution dérivé : actif prime sur simulation. */
    public BindingExecutionMode executionMode() {
        return executionEnabled ? BindingExecutionMode.ACTIVE : simulation ? BindingExecutionMode.SIMULATION : BindingExecutionMode.DISABLED;
    }

    /** Pose le mode (exclusif) ; ne touche pas à la double validation du 1ᵉʳ ordre réel. */
    public void applyExecutionMode(BindingExecutionMode mode) {
        executionEnabled = mode == BindingExecutionMode.ACTIVE;
        simulation = mode == BindingExecutionMode.SIMULATION;
    }

    /** Double validation du 1ᵉʳ ordre réel : l'admin arme ({@code firstLiveApprovedAt}), puis l'utilisateur confirme. */
    @Column(name = "first_live_approved_at")
    private Instant firstLiveApprovedAt;

    @Column(name = "first_live_confirmed_at")
    private Instant firstLiveConfirmedAt;
    /**
     * Date de retrait du lien par l'utilisateur. Non nul => lien archivé : ignoré par le run, les plans, les listes et
     * l'exécution, mais ligne et historique (runs) conservés ; candidat à une purge après un délai. Réactivé si
     * l'utilisateur relie à nouveau le même (actif, wallet).
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** Les deux validations du 1ᵉʳ ordre réel sont-elles données ? */
    public boolean firstLiveValidated() {
        return firstLiveApprovedAt != null && firstLiveConfirmedAt != null;
    }

    /** Mémorise le résultat du dernier {@code BindingCheck} (le moteur d'exécution relit ce statut). */
    public void recordCheck(BindingCheckResult result, Instant at) {
        tradability = result.status();
        quoteMember = result.quoteMember();
        routeKey = result.routeKey();
        tradabilityCheckedAt = at;
    }
}
