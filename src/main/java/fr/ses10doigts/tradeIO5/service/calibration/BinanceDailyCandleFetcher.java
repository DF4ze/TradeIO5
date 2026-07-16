package fr.ses10doigts.tradeIO5.service.calibration;

import com.binance.connector.client.impl.SpotClientImpl;
import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetch direct de l'historique D1 (daily) complet depuis l'API publique Binance, en contournant
 * volontairement {@code BinanceMarketDataApiClient}/{@code MarketDatasetEngine}/{@code CandleRepository}
 * : ce pipeline (utilisé par le reste de l'app) est câblé en dur sur H1
 * ({@code Bucket.BASE_TIME_FRAME}) et le cache DB associé ne couvre que ~2 mois d'historique — bien
 * trop court pour détecter des zones "consolidation" qui ont besoin de plusieurs années
 * (cf. tools/calibration/export_zones_v2.py, qui fetch depuis 2017-08-17). Étendre
 * {@code BinanceMarketDataApiClient.NATIVE_INTERVALS} à D1 casserait
 * {@code BinanceMarketDataApiClientTest#nativeInterval_throwsForUnsupportedTimeFrame}, d'où ce
 * client autonome plutôt qu'une modification du pipeline de données existant.
 * <p>
 * Aucune clé API requise pour l'endpoint public {@code /api/v3/klines}. Historique mis en cache en
 * mémoire (par symbole) : premier appel = fetch complet paginé depuis {@link #DEFAULT_START} ;
 * appels suivants = re-fetch uniquement de la queue récente (avec recouvrement de sécurité) tant
 * que le cache a moins de {@link #REFRESH_INTERVAL} d'ancienneté, pour ne pas re-télécharger ~9 ans
 * d'historique à chaque chargement de page.
 */
@Service
public class BinanceDailyCandleFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(BinanceDailyCandleFetcher.class);

    private static final String INTERVAL_1D = "1d";
    private static final int MAX_LIMIT = 1000;
    private static final Instant DEFAULT_START = Instant.parse("2017-01-01T00:00:00Z");
    private static final java.time.Duration REFRESH_INTERVAL = java.time.Duration.ofMinutes(15);
    private static final int OVERLAP_SAFETY_DAYS = 5;

    private final SpotClientImpl spotClient = new SpotClientImpl();
    private final Map<String, CachedHistory> cache = new ConcurrentHashMap<>();

    private record CachedHistory(List<DailyCandle> candles, Instant fetchedAt) {
    }

    /**
     * Retourne l'historique D1 complet pour le symbole donné (depuis {@link #DEFAULT_START}
     * jusqu'à maintenant), en réutilisant le cache mémoire si assez récent.
     */
    public synchronized List<DailyCandle> fetchFullHistory(String symbol) {
        Instant now = Instant.now();
        CachedHistory cached = cache.get(symbol);

        if (cached == null) {
            LOGGER.info("Aucun cache pour {} : fetch complet depuis {}", symbol, DEFAULT_START);
            List<DailyCandle> all = fetchRange(symbol, DEFAULT_START, now);
            cache.put(symbol, new CachedHistory(all, now));
            return all;
        }

        if (java.time.Duration.between(cached.fetchedAt(), now).compareTo(REFRESH_INTERVAL) < 0) {
            return cached.candles();
        }

        Instant refetchSince = cached.candles().isEmpty()
                ? DEFAULT_START
                : cached.candles().getLast().date()
                        .minusDays(OVERLAP_SAFETY_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<DailyCandle> fresh = fetchRange(symbol, refetchSince, now);
        List<DailyCandle> merged = mergeTail(cached.candles(), fresh);
        cache.put(symbol, new CachedHistory(merged, now));
        return merged;
    }

    private List<DailyCandle> mergeTail(List<DailyCandle> base, List<DailyCandle> fresh) {
        if (fresh.isEmpty()) {
            return base;
        }
        LocalDate firstFreshDate = fresh.getFirst().date();
        List<DailyCandle> merged = new ArrayList<>();
        for (DailyCandle c : base) {
            if (c.date().isBefore(firstFreshDate)) {
                merged.add(c);
            }
        }
        merged.addAll(fresh);
        return merged;
    }

    private List<DailyCandle> fetchRange(String symbol, Instant start, Instant end) {
        List<DailyCandle> result = new ArrayList<>();
        long startMs = start.toEpochMilli();
        long endMs = end.toEpochMilli();

        while (startMs < endMs) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("interval", INTERVAL_1D);
            params.put("startTime", startMs);
            params.put("endTime", endMs);
            params.put("limit", MAX_LIMIT);

            String response = spotClient.createMarket().klines(params);
            JSONArray raw = new JSONArray(response);
            if (raw.isEmpty()) {
                break;
            }

            for (int i = 0; i < raw.length(); i++) {
                JSONArray k = raw.getJSONArray(i);
                long openTime = k.getLong(0);
                LocalDate date = Instant.ofEpochMilli(openTime).atZone(ZoneOffset.UTC).toLocalDate();
                result.add(new DailyCandle(
                        date,
                        Double.parseDouble(k.getString(1)),
                        Double.parseDouble(k.getString(2)),
                        Double.parseDouble(k.getString(3)),
                        Double.parseDouble(k.getString(4)),
                        Double.parseDouble(k.getString(5))));
            }

            long lastOpenTime = raw.getJSONArray(raw.length() - 1).getLong(0);
            long nextStart = lastOpenTime + ChronoUnit.DAYS.getDuration().toMillis();
            if (nextStart <= startMs || raw.length() < MAX_LIMIT) {
                break;
            }
            startMs = nextStart;
        }
        return result;
    }
}
