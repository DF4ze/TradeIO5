package fr.ses10doigts.tradeIO5.model.entity.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Plusieurs {@link Asset} regroupés sous un seul « actif » affiché et compté (ex : USD = USDC + USDT). Les membres sont
 * portés par {@link AssetGroupMember}. Donnée de paramétrage : semée par {@code AssetGroupInitializer}, jamais écrasée.
 */
@Entity
@Table(name = "asset_group",
        uniqueConstraints = @UniqueConstraint(name = "uk_asset_group_code", columnNames = "code"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetGroup {

    /** Code du groupe des stablecoins dollar (USDC, USDT). Seule définition de ce code. */
    public static final String USD = "USD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Code affiché et clé d'accès (ex : USD). */
    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssetGroupValuation valuation;
}
