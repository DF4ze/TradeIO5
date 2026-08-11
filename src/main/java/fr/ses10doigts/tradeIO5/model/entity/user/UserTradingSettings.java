package fr.ses10doigts.tradeIO5.model.entity.user;

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

/**
 * Réglages de trading propres à un utilisateur. Palier 2 (2026-08) : ne porte pour l'instant que
 * le curseur de risque continu (étude §4/§11). Le calcul de sizing qui le consommera n'existe pas
 * encore (§4/§7, volontairement hors scope de ce lot).
 */
@Entity
@Table(name = "user_trading_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_trading_settings_user", columnNames = "user_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTradingSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Curseur de risque continu, 0 = conservateur, 10 = super agressif. Cf. étude §4. */
    @Column(nullable = false)
    private int riskCursor;
}
