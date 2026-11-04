package fr.ses10doigts.tradeIO5.model.entity.user;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * Historique append-only des changements de réglages de modulation d'un utilisateur (profil choisi ou paramètre modifié à la
 * main) : une ligne par changement effectif, avec les valeurs avant / après et le profil avant / après. Jamais modifiée.
 */
@Entity
@Table(name = "user_modulation_history", indexes = @Index(name = "idx_user_modulation_history_user_at", columnList = "user_id, occurred_at"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModulationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ModulationChangeSource source;

    /** Profil avant le changement (nul = aucun profil jamais choisi). */
    @Enumerated(EnumType.STRING)
    @Column(name = "profile_before", length = 16)
    private StartingProfile profileBefore;

    /** Profil après le changement ({@code CUSTOM} après une modification manuelle). */
    @Enumerated(EnumType.STRING)
    @Column(name = "profile_after", length = 16)
    private StartingProfile profileAfter;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "exposureMin", column = @Column(name = "before_exposure_min")),
            @AttributeOverride(name = "exposureMax", column = @Column(name = "before_exposure_max")),
            @AttributeOverride(name = "power", column = @Column(name = "before_power")),
            @AttributeOverride(name = "bias", column = @Column(name = "before_bias")),
            @AttributeOverride(name = "regulationMarginPct", column = @Column(name = "before_margin_pct"))})
    private ModulationValues before;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "exposureMin", column = @Column(name = "after_exposure_min")),
            @AttributeOverride(name = "exposureMax", column = @Column(name = "after_exposure_max")),
            @AttributeOverride(name = "power", column = @Column(name = "after_power")),
            @AttributeOverride(name = "bias", column = @Column(name = "after_bias")),
            @AttributeOverride(name = "regulationMarginPct", column = @Column(name = "after_margin_pct"))})
    private ModulationValues after;
}
