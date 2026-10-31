package fr.ses10doigts.tradeIO5.model.entity.user;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Réglages de trading propres à un utilisateur : curseur de risque continu (étude §4/§11) et réglages de modulation Rainbow
 * (bande d'exposition, puissance, biais). Les réglages de modulation sont <b>nullables</b> (sur base existante,
 * {@code ddl-auto=update} initialiserait des primitives à 0) : {@code null} => valeur neutre
 * ({@code ModulationDefaults.NEUTRAL_*}). Rien n'est encore appliqué aux montants.
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

    /** Borne basse de la bande d'exposition, en % (null => 0). */
    private Double exposureMin;

    /** Borne haute de la bande d'exposition, en % (null => 100). */
    private Double exposureMax;

    /** Puissance : multiplicateur des montants d'achat et de vente (null => 1). */
    private Double power;

    /** Biais achat/vente, signé (null => 0). */
    private Double bias;

    /** Marge de régulation choisie par l'utilisateur, en % de la largeur de bande ; nulle = défaut admin. */
    @Column(name = "regulation_margin_pct")
    private Double regulationMarginPct;

    /** Profil de départ choisi à l'onboarding (null => aucun) ; il a posé la bande d'exposition de départ. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private StartingProfile startingProfile;

    /** Thème d'affichage (null => sombre). */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private UiTheme theme;

    /** Mode d'affichage ; nul = simple. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private DisplayMode displayMode;

    /** Types d'alertes coupés par l'utilisateur, noms séparés par des virgules (null => aucun). */
    @Column(length = 200)
    private String alertsDisabled;

    /** Plafond personnel par ordre, en USD, toujours <= plafond absolu (null => plafond absolu seul). */
    private Double maxOrderUsd;

    /** Plafond personnel par jour, en USD, toujours <= plafond absolu (null => plafond absolu seul). */
    private Double maxDayUsd;
}
