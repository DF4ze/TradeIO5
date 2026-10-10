package fr.ses10doigts.tradeIO5.service.connector.balance;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Cache mémoire court des soldes, clé = {@code credential.getId()}. Seule une lecture réussie est mise en cache : une
 * exception du fetch se propage et ne laisse aucune entrée.
 */
@RequiredArgsConstructor
public class BalanceCacheManager {

    // FIXME : Parametrize
    private static final Duration TTL = Duration.ofSeconds(60);

    private record CacheEntry(Map<String, BigDecimal> balances, Instant fetchedAt) {
    }

    private final DomainClock clock;

    private final Map<String, CacheEntry> cacheMap = new ConcurrentHashMap<>();

    public Map<String, BigDecimal> getBalances(Function<ApiCredential, Map<String, BigDecimal>> fetcher,
            ApiCredential credential) {
        String key = String.valueOf(credential.getId());
        Instant now = clock.now();
        CacheEntry entry = cacheMap.get(key);
        if (entry != null && Duration.between(entry.fetchedAt(), now).compareTo(TTL) <= 0) {
            return entry.balances();
        }

        Map<String, BigDecimal> fresh = fetcher.apply(credential);
        cacheMap.put(key, new CacheEntry(fresh, now));
        return fresh;
    }
}
