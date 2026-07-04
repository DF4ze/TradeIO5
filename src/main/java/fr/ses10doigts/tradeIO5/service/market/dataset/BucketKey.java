package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

/**
 * Identifie un flux natif unique : symbole + TimeFrame + source + paramètre provider.
 * <p>
 * Contrairement à {@link MarketDatasetRequest}, ne contient PAS {@code endTime} ni
 * {@code lookBack} : ces deux champs décrivent la fenêtre demandée par un appelant à un
 * instant donné et varient à chaque appel (ex: {@code Instant.now()} à chaque tick), sans
 * pour autant changer le flux sous-jacent. Ils ne doivent donc pas faire partie de la clé
 * utilisée pour retrouver/partager l'état ({@link MarketDatasetState}) et le {@link Bucket}
 * associés à ce flux.
 */
record BucketKey(
        String symbol,
        TimeFrame timeFrame,
        MarketDataSource source,
        Object providerParam
) {

    static BucketKey from(MarketDatasetRequest request) {
        return new BucketKey(
                request.symbol(),
                request.timeFrame(),
                request.source(),
                request.providerParam()
        );
    }
}
