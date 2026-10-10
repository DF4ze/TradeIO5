package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/**
 * Wallet fictif d'un preset (1-1, supprimé avec lui). Distinct de l'entité {@code Wallet} (exchange).
 * Seul stablecoin : USDC. N'est modifié que par la passe 23:55 (cf. {@code RainbowLiveRunService}).
 */
@Entity
@Table(name = "rainbow_live_mock_wallet")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveMockWallet implements PortfolioView {

    /** Tolérance d'arrondi flottant sur les contrôles de solde. */
    private static final double EPSILON = 1e-9;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "preset_id", nullable = false, unique = true)
    private RainbowLivePreset preset;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    private double cashUsd;

    private double positionQuantity;

    @Column(nullable = false)
    private Instant updatedAt;

    @Override
    public double cash() {
        return cashUsd;
    }

    @Override
    public double quantity(String symbol) {
        return assetSymbol.equals(symbol) ? positionQuantity : 0.0;
    }

    /** Achat : débite {@code amountUsdc}, crédite {@code qty}. Refusé si cash insuffisant (plus de cash ⇒ plus d'achat). */
    public void applyBuy(double amountUsdc, double qty) {
        if (amountUsdc <= 0 || qty <= 0) {
            throw new IllegalArgumentException("Achat invalide : montant=" + amountUsdc + " qty=" + qty);
        }
        if (amountUsdc > cashUsd + EPSILON) {
            throw new IllegalStateException("Cash insuffisant : achat " + amountUsdc + " USDC > cash " + cashUsd);
        }
        cashUsd = Math.max(0.0, cashUsd - amountUsdc);
        positionQuantity += qty;
    }

    /** Vente : débite {@code qty}, crédite {@code proceedsUsdc}. Refusée si position insuffisante. */
    public void applySell(double proceedsUsdc, double qty) {
        if (proceedsUsdc <= 0 || qty <= 0) {
            throw new IllegalArgumentException("Vente invalide : produit=" + proceedsUsdc + " qty=" + qty);
        }
        if (qty > positionQuantity + EPSILON) {
            throw new IllegalStateException("Position insuffisante : vente " + qty + " > position " + positionQuantity);
        }
        positionQuantity = Math.max(0.0, positionQuantity - qty);
        cashUsd += proceedsUsdc;
    }
}
