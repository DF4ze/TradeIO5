package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDatasetRequest;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Identifie un flux natif unique : symbole + source + paramètre provider. Le TimeFrame demandé n'en
 * fait PAS partie : le {@link Bucket} stocke toujours du H1 et agrège à la volée, donc tous les TF
 * demandés (H1, D1, W1...) d'un même symbole/source partagent un seul Bucket, une seule fraîcheur
 * ({@code lastUpdate}) et un seul refetch par bougie H1.
 * <p>
 * Contrairement à {@link MarketDatasetRequest}, ne contient PAS {@code endTime} ni
 * {@code lookBack} : ces deux champs décrivent la fenêtre demandée par un appelant à un
 * instant donné et varient à chaque appel (ex: {@code Instant.now()} à chaque tick), sans
 * pour autant changer le flux sous-jacent. Ils ne doivent donc pas faire partie de la clé
 * utilisée pour retrouver/partager l'état ({@link MarketDatasetState}) et le {@link Bucket}
 * associés à ce flux.
 */
public record BucketKey(
        String symbol,
        MarketDataSource source,
        Object providerParam
) {

    static BucketKey from(MarketDatasetRequest request) {
        return new BucketKey(
                request.symbol(),
                request.source(),
                request.providerParam()
        );
    }
}
