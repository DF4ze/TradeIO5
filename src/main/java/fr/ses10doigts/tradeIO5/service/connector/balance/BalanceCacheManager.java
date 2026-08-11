package fr.ses10doigts.tradeIO5.service.connector.balance;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class BalanceCacheManager {

    // FIXME : Parametrize
    private static final long TTL_MS = 60*1000;

    private static class CacheEntry {
        Map<String, BigDecimal> balances;
        long timestamp;

        CacheEntry(Map<String, BigDecimal> balances) {
            this.balances = balances;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TTL_MS;
        }
    }

    private final Map<String, CacheEntry> cacheMap = new ConcurrentHashMap<>();

    /**
     * Palier 2 (étape 3, 2026-08) : la clé de cache est dérivée en interne à partir de
     * {@code credential.getId()} (identifiant stable), plutôt que reconstruite par chaque
     * appelant via {@code credential.getApiKey() + ":" + baseUrl}. Le paramètre historique
     * s'appelait "asset" mais n'a jamais été un asset : c'était déjà cette clé composite,
     * construite à l'identique dans {@code BinanceApiClient}/{@code KrakenApiClient}.
     */
    public Map<String, BigDecimal> getBalances(BalanceProvider provider, ApiCredential credential) {
        String key = String.valueOf(credential.getId());
        CacheEntry entry = cacheMap.get(key);
        if (entry != null && !entry.isExpired()) {
            return entry.balances;
        }

		Map<String, BigDecimal> fresh = provider.fetchAllBalances(credential);
        cacheMap.put(key, new CacheEntry(fresh));
        return fresh;
    }
}