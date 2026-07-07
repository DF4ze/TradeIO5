package fr.ses10doigts.tradeIO5.repository.market;

import fr.ses10doigts.tradeIO5.model.entity.market.CandleEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Accès aux bougies mises en cache par {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.CachingMarketDataApiClient}.
 * <p>
 * L'index de la contrainte unique {@code uk_candle_source_pair_tf_ts} (source, pair, time_frame,
 * timestamp) sert nativement à {@link #findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc} :
 * préfixe gauche en égalité (source/pair/timeFrame) + range sur la dernière colonne (timestamp) —
 * pas besoin d'un index supplémentaire.
 */
public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    List<CandleEntity> findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
            MarketDataSource source, String pair, TimeFrame timeFrame, Instant since, Instant until
    );
}
