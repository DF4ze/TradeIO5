package fr.ses10doigts.tradeIO5.service.connector.balance;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("BalanceCacheManager")
class BalanceCacheManagerTest {

    private final FixedDomainClock clock = new FixedDomainClock(Instant.parse("2026-10-09T20:00:00Z"));
    private final BalanceCacheManager cacheManager = new BalanceCacheManager(clock);
    private final AtomicInteger callCount = new AtomicInteger();
    private final Function<ApiCredential, Map<String, BigDecimal>> fetcher = credential -> {
        callCount.incrementAndGet();
        return Map.of("BTC", BigDecimal.ONE);
    };

    @Test
    @DisplayName("Deux ApiCredential différents (ids différents) ont des entrées de cache distinctes")
    void getBalances_differentCredentials_doNotShareCacheEntry() {
        ApiCredential credentialA = ApiCredential.builder().id(1L).apiKey("keyA").build();
        ApiCredential credentialB = ApiCredential.builder().id(2L).apiKey("keyB").build();

        cacheManager.getBalances(fetcher, credentialA);
        cacheManager.getBalances(fetcher, credentialB);
        assertEquals(2, callCount.get());

        cacheManager.getBalances(fetcher, credentialA);
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("TTL 60 s sur l'horloge injectée : servi depuis le cache à 60 s, relu après")
    void getBalances_expiresAfterTtl() {
        ApiCredential credential = ApiCredential.builder().id(1L).build();

        cacheManager.getBalances(fetcher, credential);
        clock.advance(Duration.ofSeconds(60));
        cacheManager.getBalances(fetcher, credential);
        assertEquals(1, callCount.get());

        clock.advance(Duration.ofMillis(1));
        cacheManager.getBalances(fetcher, credential);
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("Un échec du fetch se propage et n'est pas mis en cache ; une map vide valide l'est")
    void getBalances_failureNotCached_emptyMapCached() {
        ApiCredential credential = ApiCredential.builder().id(1L).build();
        Function<ApiCredential, Map<String, BigDecimal>> failing = c -> {
            callCount.incrementAndGet();
            throw new BalanceUnavailableException("panne");
        };

        assertThrows(BalanceUnavailableException.class, () -> cacheManager.getBalances(failing, credential));
        assertThrows(BalanceUnavailableException.class, () -> cacheManager.getBalances(failing, credential));
        assertEquals(2, callCount.get());

        Function<ApiCredential, Map<String, BigDecimal>> empty = c -> {
            callCount.incrementAndGet();
            return Map.of();
        };
        cacheManager.getBalances(empty, credential);
        cacheManager.getBalances(empty, credential);
        assertEquals(3, callCount.get());
    }
}
