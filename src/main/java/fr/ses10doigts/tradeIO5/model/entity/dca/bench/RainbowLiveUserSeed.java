package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Marqueur « presets par défaut déjà semés » d'un utilisateur (hors entité {@code User}, dont le
 * {@code @AllArgsConstructor} casse les appels positionnels). Présent ⇒ {@code ensureDefaultPresets} est un no-op :
 * supprimer tous les presets d'un actif = ne plus le suivre.
 */
@Entity
@Table(name = "rainbow_live_user_seed",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_user_seed_user", columnNames = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveUserSeed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private Instant seededAt;
}
