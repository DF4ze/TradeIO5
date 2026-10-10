package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

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

import java.time.Instant;

/**
 * Lien (utilisateur, actif) -> preset « live » + wallet réel (lecture seule). L'unicité (user, actif) garantit un seul
 * preset live par actif ; changer de preset live = modifier ce lien (l'ancien preset reste un preset de simulation, son
 * historique est conservé). Donnée utilisateur : créée par l'API, jamais par un initializer.
 */
@Entity
@Table(name = "rainbow_live_binding",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_binding_user_asset",
                columnNames = {"user_id", "asset_symbol"}))
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

    /** Le preset live de l'actif. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "preset_id", nullable = false)
    private RainbowLivePreset preset;

    /** Wallet réel lu (exchange + credential). */
    @ToString.Exclude
    @ManyToOne(optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /** Part (0-100 %) de la position réelle offerte à la stratégie, dynamique (suit le wallet). */
    @Column(nullable = false)
    private double bagPercent;

    /** Ordre de passage du cash commun (plus petit = servi en premier). */
    @Column(nullable = false)
    private int priority;

    /** Dernier statut de tradabilité de (actif, exchange) ; nul tant que le binding n'a jamais été vérifié. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private BindingCheckStatus tradability;

    /** Membre du groupe USD dans lequel l'actif est coté, et paire de passerelle éventuelle (dernier contrôle). */
    @Column(name = "quote_member", length = 16)
    private String quoteMember;

    @Column(name = "bridge_pair", length = 32)
    private String bridgePair;

    @Column(name = "tradability_checked_at")
    private Instant tradabilityCheckedAt;

    /**
     * Interrupteur d'exécution du binding (défaut faux). Faux => son plan d'ordres est marqué {@code DISABLED} (visible,
     * sans étape) et rien n'est envoyé. L'utilisateur active son propre binding ; l'admin arme le 1ᵉʳ ordre réel.
     */
    @Column(name = "execution_enabled", nullable = false)
    private boolean executionEnabled;

    /** Double validation du 1ᵉʳ ordre réel : l'admin arme ({@code firstLiveApprovedAt}), puis l'utilisateur confirme. */
    @Column(name = "first_live_approved_at")
    private Instant firstLiveApprovedAt;

    @Column(name = "first_live_confirmed_at")
    private Instant firstLiveConfirmedAt;

    /** Les deux validations du 1ᵉʳ ordre réel sont-elles données ? */
    public boolean firstLiveValidated() {
        return firstLiveApprovedAt != null && firstLiveConfirmedAt != null;
    }

    /** Mémorise le résultat du dernier {@code BindingCheck} (le moteur d'exécution relit ce statut). */
    public void recordCheck(BindingCheckResult result, Instant at) {
        tradability = result.status();
        quoteMember = result.quoteMember();
        bridgePair = result.bridgePair();
        tradabilityCheckedAt = at;
    }
}
