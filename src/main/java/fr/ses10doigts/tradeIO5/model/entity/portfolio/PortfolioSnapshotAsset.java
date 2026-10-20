package fr.ses10doigts.tradeIO5.model.entity.portfolio;

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
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Ligne d'actif d'un {@link PortfolioSnapshot} : quantité, prix et valeur (nuls si le prix manque). Immuable. */
@Entity
@Table(name = "portfolio_snapshot_asset",
        uniqueConstraints = @UniqueConstraint(name = "uk_portfolio_snapshot_asset_snapshot_asset",
                columnNames = {"snapshot_id", "asset_symbol"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSnapshotAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private PortfolioSnapshot snapshot;

    @Column(name = "asset_symbol", nullable = false)
    private String assetSymbol;

    @Column(nullable = false)
    private double quantity;

    @Column(name = "price")
    private Double price;

    @Column(name = "asset_value")
    private Double value;
}
