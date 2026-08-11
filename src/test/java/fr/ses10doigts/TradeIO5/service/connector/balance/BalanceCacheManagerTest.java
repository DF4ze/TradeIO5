package fr.ses10doigts.tradeIO5.service.connector.balance;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Palier 2 (étape 3, nettoyage optionnel) : la clé de cache est désormais dérivée de
 * {@code credential.getId()} plutôt que reconstruite par l'appelant. Vérifie que deux
 * {@code ApiCredential} différents ne partagent pas d'entrée de cache.
 */
@DisplayName("BalanceCacheManager")
class BalanceCacheManagerTest {

    @Test
    @DisplayName("Deux ApiCredential différents (ids différents) ont des entrées de cache distinctes")
    void getBalances_differentCredentials_doNotShareCacheEntry() {
        BalanceCacheManager cacheManager = new BalanceCacheManager();

        ApiCredential credentialA = ApiCredential.builder().id(1L).apiKey("keyA").build();
        ApiCredential credentialB = ApiCredential.builder().id(2L).apiKey("keyB").build();

        AtomicInteger callCount = new AtomicInteger(0);
        BalanceProvider provider = credential -> {
            callCount.incrementAndGet();
            return Map.of("BTC", BigDecimal.ONE);
        };

        cacheManager.getBalances(provider, credentialA);
        cacheManager.getBalances(provider, credentialB);

        // Chaque credential a déclenché son propre appel réseau (pas de collision de clé de cache),
        // même si un second appel sur la même credential resterait servi depuis le cache.
        assertEquals(2, callCount.get());

        cacheManager.getBalances(provider, credentialA);
        assertEquals(2, callCount.get());
    }
}
