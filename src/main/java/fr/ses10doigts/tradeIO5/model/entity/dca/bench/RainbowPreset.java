package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Jeu de paramètres Rainbow DCA ATR d'un actif + fenêtre d'analyse. <b>Neutre</b> : aucun type UP / DOWN, c'est
 * {@link AssetAffectation} qui l'installe sur UP ou DOWN (le même preset peut occuper les deux).
 */
@Entity
@Table(name = "rainbow_preset",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_rainbow_preset_owner_asset_name", columnNames = {"owner_id", "asset_symbol", "name"}),
                @UniqueConstraint(name = "uk_rainbow_preset_asset_base_role", columnNames = {"asset_symbol", "base_role"})})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowPreset implements ExpertPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Symbole nu de l'actif : BTC | ETH | PAXG. */
    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Provider de référence (données de calibrage) ; nul = non précisé. */
    @Column(name = "reference_provider", length = 32)
    private String referenceProvider;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private PresetOrigin origin;

    /** Propriétaire ; nul pour un preset SYSTEM. */
    @ManyToOne
    @JoinColumn(name = "owner_id")
    private User owner;

    /** {@link BasePresetRole#RAINBOW_BULL} / {@link BasePresetRole#RAINBOW_BEAR} pour un preset SYSTEM semé ; nul sinon. */
    @Enumerated(EnumType.STRING)
    @Column(name = "base_role", length = 16)
    private BasePresetRole baseRole;

    private int revision;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Fenêtre rejouée par le moteur, en mois. */
    private int analysisWindowMonths;

    @Embedded
    private RainbowAtrConfig config;
}
