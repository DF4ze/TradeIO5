package fr.ses10doigts.tradeIO5.model.entity.market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Liste noire admin des actifs : un actif listé ne peut plus recevoir de nouveau lien (désactivation globale). L'administration
 * (ajout / retrait, journal d'audit) arrive avec le lot Admin ; ce lot ne porte que le stockage et la lecture par
 * {@code AssetEligibilityService}.
 */
@Entity
@Table(name = "asset_blocklist", uniqueConstraints = @UniqueConstraint(name = "uk_asset_blocklist_asset", columnNames = "asset_symbol"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetBlocklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
