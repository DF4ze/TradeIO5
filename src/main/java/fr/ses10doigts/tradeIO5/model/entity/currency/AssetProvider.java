package fr.ses10doigts.tradeIO5.model.entity.currency;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
import lombok.NoArgsConstructor;

/**
 * Table de jointure (asset, exchange) : pour chaque {@link Asset}, porte l'appellation propre à
 * chaque exchange candidat pour le fallback multi-provider, son ordre de préférence et la limite
 * d'historique connue. Cf. docs/etudes/etude-fallback-multi-provider-marketdata.md §3 (étape 5).
 * <p>
 * Ne porte aucune logique métier à ce stade : ni seeding (cf. {@code AssetInitializer}, étape 6),
 * ni branchement dans le moteur de fallback (cf. {@code MarketDatasetEngine}, étape 7).
 */
@Entity
@Table(name = "asset_provider",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_asset_provider_asset_source", columnNames = {"asset_id", "source"})
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MarketDataSource source;

    // Appellation propre à l'exchange (ex : BTCUSDT chez Binance, XXBTZUSD chez Kraken, BTC-USDT chez OKX).
    @Column(name = "provider_symbol", nullable = false, length = 50)
    private String providerSymbol;

    // Ordre de préférence pour le fallback : 0 = favori/premier essayé.
    @Column(nullable = false)
    private int priority;

    // Kill switch manuel.
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    // null = illimité (Binance, OKX une fois history-candles branché) ; valeur non nulle pour
    // les providers à historique borné (ex : Kraken, ~25j, cf. DcaCalculatorService.NON_BINANCE_MAX_HORIZON_DAYS).
    @Column(name = "max_horizon_days")
    private Integer maxHorizonDays;
}
