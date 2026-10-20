package fr.ses10doigts.tradeIO5.model.entity.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Instantané quotidien <b>immuable</b> d'un wallet d'un utilisateur (jour UTC), écrit une fois par la passe 23:55 UTC et jamais
 * modifié. Les valeurs sont nulles (jamais 0) quand la lecture n'est pas {@link PortfolioStatus#OK} ou qu'un prix manque.
 * Le provider se déduit de {@code wallet.exchange}. Supprimé avec son utilisateur ou son wallet.
 */
@Entity
@Table(name = "portfolio_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_portfolio_snapshot_user_wallet_day",
                columnNames = {"user_id", "wallet_id", "snapshot_day"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ToString.Exclude
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    /** Jour UTC. */
    @Column(name = "snapshot_day", nullable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PortfolioStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_basis", nullable = false)
    private PortfolioPriceBasis priceBasis;

    /** Stable (cash du groupe USD). */
    @Column(name = "cash_usd")
    private Double cashUsd;

    /** Valeur des actifs (quantité x prix). */
    @Column(name = "assets_value")
    private Double assetsValue;

    /** Patrimoine = stable + actifs. */
    @Column(name = "total_value")
    private Double totalValue;

    /** Part des actifs dans le patrimoine, en %. */
    @Column(name = "exposure_pct")
    private Double exposurePct;

    /** Bande d'exposition (réglages utilisateur) en vigueur ce jour-là, en %. */
    @Column(name = "exposure_min")
    private Double exposureMin;

    @Column(name = "exposure_max")
    private Double exposureMax;

    /** Heure de lecture des soldes. */
    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @ToString.Exclude
    @Builder.Default
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PortfolioSnapshotAsset> assets = new ArrayList<>();
}
