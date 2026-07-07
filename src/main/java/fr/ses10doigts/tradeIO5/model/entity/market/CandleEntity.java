package fr.ses10doigts.tradeIO5.model.entity.market;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Bougie persistée en cache (cf. docs/etude-cache-db-candles-h1.md).
 * <p>
 * Suffixe {@code Entity} pour éviter la collision avec le DTO
 * {@link fr.ses10doigts.tradeIO5.model.dto.market.MarketData} déjà existant (même convention que
 * {@link fr.ses10doigts.tradeIO5.model.entity.tree.EventEntity}).
 * <p>
 * Volontairement sans {@code User}/{@code Wallet} associé : les candles sont des données
 * publiques, une seule table partagée sert tous les utilisateurs de l'app — cohérent avec le
 * commentaire déjà présent sur {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient}.
 * <p>
 * Une ligne n'est écrite qu'une fois la bougie close (jamais celle en cours) : cf.
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.CachingMarketDataApiClient}.
 * Une bougie close ne change donc plus jamais — pas d'invalidation ni de notion de fraîcheur à
 * gérer ici (exception théorique : correction tardive d'un exchange, risque assumé, non résolu).
 */
@Entity
@Table(name = "candle",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_candle_source_pair_tf_ts",
           columnNames = {"source", "pair", "time_frame", "timestamp"}
       ))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketDataSource source;   // BINANCE, KRAKEN, OKX...

    @Column(nullable = false, length = 20)
    private String pair;               // BTCUSDT

    @Enumerated(EnumType.STRING)
    @Column(name = "time_frame", nullable = false, length = 10)
    private TimeFrame timeFrame;       // H1 aujourd'hui, extensible

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal open;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal high;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal low;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal close;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal volume;
}
