package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Affectation (utilisateur, actif) : 1 {@link TrendPreset} + 1 {@link RainbowPreset} pour UP + 1 pour DOWN. Complète
 * (les trois renseignés) = exploitable par le moteur live. Plusieurs par (utilisateur, actif), de nom unique par
 * (utilisateur, actif) : une instance <b>Système</b> (trio des presets de base, non modifiable, « BTC Système ») et des
 * Affectations <b>Utilisateur</b>. C'est l'unité du moteur live : les runs, l'état moteur et les liens (bindings) lui sont
 * rattachés. Elle est jouée par la passe si au moins un lien non archivé la référence (aucun interrupteur propre : on joue
 * le lien) ; un lien en référence exactement une.
 */
@Entity
@Table(name = "asset_affectation",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_affectation_user_asset_name", columnNames = {"user_id", "asset_symbol", "name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetAffectation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Nom unique par (utilisateur, actif) ; nul sur une Affectation antérieure à la nomination (repli sur {@link #label()}). */
    @Column(length = 64)
    private String name;

    /** Instance Système : presets de base, non modifiable ni supprimable (l'état moteur reste propre à l'utilisateur). */
    @Column(name = "system_instance", nullable = false)
    private boolean system;

    @ManyToOne
    @JoinColumn(name = "trend_preset_id")
    private TrendPreset trendPreset;

    @ManyToOne
    @JoinColumn(name = "rainbow_up_id")
    private RainbowPreset rainbowUp;

    @ManyToOne
    @JoinColumn(name = "rainbow_down_id")
    private RainbowPreset rainbowDown;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public boolean isComplete() {
        return trendPreset != null && rainbowUp != null && rainbowDown != null;
    }

    /** Nom affiché : le nom donné, à défaut le libellé calculé. */
    public String displayName() {
        return name != null && !name.isBlank() ? name : label();
    }

    /** Libellé court : nom du preset Rainbow (un seul si UP et DOWN sont identiques, sinon « UP / DOWN »). */
    public String label() {
        if (rainbowUp == null || rainbowDown == null) {
            return assetSymbol;
        }
        return rainbowUp.getId().equals(rainbowDown.getId()) ? rainbowUp.getName()
                : rainbowUp.getName() + " / " + rainbowDown.getName();
    }
}
