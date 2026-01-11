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

	public Map<String, BigDecimal> getBalances(String asset, BalanceProvider provider, ApiCredential credential) {
        CacheEntry entry = cacheMap.get(asset);
        if (entry != null && !entry.isExpired()) {
            return entry.balances;
        }

		Map<String, BigDecimal> fresh = provider.fetchAllBalances(credential);
        cacheMap.put(asset, new CacheEntry(fresh));
        return fresh;
    }
}