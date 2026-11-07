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
 * (les trois renseignés) = exploitable par le moteur live. Une seule par (utilisateur, actif). C'est l'unité du moteur
 * live : les runs, l'état moteur et les liens (bindings) lui sont rattachés.
 */
@Entity
@Table(name = "asset_affectation",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_affectation_user_asset", columnNames = {"user_id", "asset_symbol"}))
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

    @ManyToOne
    @JoinColumn(name = "trend_preset_id")
    private TrendPreset trendPreset;

    @ManyToOne
    @JoinColumn(name = "rainbow_up_id")
    private RainbowPreset rainbowUp;

    @ManyToOne
    @JoinColumn(name = "rainbow_down_id")
    private RainbowPreset rainbowDown;

    /** Joué à chaque passe quotidienne ; faux par défaut (l'utilisateur l'active). */
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public boolean isComplete() {
        return trendPreset != null && rainbowUp != null && rainbowDown != null;
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
