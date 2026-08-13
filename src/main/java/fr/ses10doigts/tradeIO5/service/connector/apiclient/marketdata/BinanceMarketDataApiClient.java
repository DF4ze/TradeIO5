package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implémentation {@link MarketDataApiClient} pour Binance.
 * <p>
 * Réutilise la lib déjà en dépendance (io.github.binance:binance-connector-java) mais sur
 * l'endpoint public GET /api/v3/klines, qui ne nécessite aucune clé API. À la différence de
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.BinanceApiClient}, ce client n'est
 * donc jamais construit à partir d'un {@code ApiCredential}.
 */
@Component
public class BinanceMarketDataApiClient implements MarketDataApiClient {

    private static final Logger logger = LoggerFactory.getLogger(BinanceMarketDataApiClient.class);

    // Seul TimeFrame ingéré nativement par Bucket aujourd'hui (cf. Bucket.BASE_TIME_FRAME)
    private static final Map<TimeFrame, String> NATIVE_INTERVALS = Map.of(TimeFrame.H1, "1h");

    // Binance Spot API error codes, doc publique "Error Codes" : -1121 = "Invalid symbol."
    private static final int ERROR_CODE_INVALID_SYMBOL = -1121;

    private final SpotClientImpl client;

    public BinanceMarketDataApiClient() {
        // Constructeur sans clé API : suffisant pour l'endpoint public /api/v3/klines
        this.client = new SpotClientImpl();
    }

    @Override
    public MarketDataSource getSource() {
        return MarketDataSource.BINANCE;
    }

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        String interval = nativeInterval(timeFrame);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("interval", interval);
        if (since != null) {
            params.put("startTime", since.toEpochMilli());
        }
        if (until != null) {
            params.put("endTime", until.toEpochMilli());
        }
        if (limit > 0) {
            params.put("limit", limit);
        }

        // Log de diagnostic (incident 2026-08-13, cf. mémoire projet) : le SpotClientImpl est
        // construit sans timeout explicite (ni connect ni read) — un appel réseau bloqué côté
        // Binance ne remonte donc aucune exception, il reste juste pendu ici indéfiniment. Ce
        // log avant/après avec durée permet de confirmer que c'est bien CE point précis qui ne
        // rend jamais la main (plutôt qu'un blocage plus haut dans la chaîne MCP -> engine).
        long startNanos = System.nanoTime();
        logger.info("Binance klines : appel réseau démarré pour {} {} params={}", symbol, interval, params);
        try {
            String response = client.createMarket().klines(params);
            logger.info("Binance klines : appel réseau terminé pour {} {} en {} ms", symbol, interval,
                    (System.nanoTime() - startNanos) / 1_000_000);
            return mapKlinesResponse(response, symbol, timeFrame);
        } catch (BinanceClientException | BinanceConnectorException e) {
            logger.warn("Failed to fetch Binance klines for {} ({}) after {} ms : {}", symbol, interval,
                    (System.nanoTime() - startNanos) / 1_000_000, e.getMessage());
            throw mapError(symbol, e);
        } catch (Exception e) {
            logger.warn("Unexpected error while fetching Binance klines for {} ({}) after {} ms : {}", symbol, interval,
                    (System.nanoTime() - startNanos) / 1_000_000, e.getMessage());
            throw mapError(symbol, e);
        }
    }

    /**
     * Traduit une erreur Binance (levée par le SDK avant tout mapping JSON, cf. {@link #getCandles})
     * vers la hiérarchie {@link MarketDataProviderException}. Isolée pour être testable sans appel
     * réseau réel, comme {@link #mapKlinesResponse}.
     * <p>
     * {@link BinanceClientException#getErrorCode()} porte le code d'erreur Binance ; {@code -1121}
     * ("Invalid symbol.", doc publique Binance Spot API "Error Codes") est le seul cas permanent
     * distingué ici. Toute autre {@link BinanceClientException}, ainsi que
     * {@link BinanceConnectorException} (erreurs réseau/connexion) ou toute autre {@link Exception}
     * inattendue, sont considérées transitoires.
     */
    static MarketDataProviderException mapError(String symbol, Exception e) {
        if (e instanceof BinanceClientException bce && bce.getErrorCode() == ERROR_CODE_INVALID_SYMBOL) {
            return new SymbolNotFoundException(MarketDataSource.BINANCE, symbol, bce.getMessage());
        }
        return new ProviderUnavailableException(MarketDataSource.BINANCE, symbol, e.getMessage(), e);
    }

    /**
     * Mappe le tableau positionnel Binance ([openTime, open, high, low, close, volume, closeTime, ...])
     * vers une liste de {@link MarketData}. Isolée de l'appel réseau pour être testable en unitaire.
     */
    static List<MarketData> mapKlinesResponse(String response, String symbol, TimeFrame timeFrame) {
        JSONArray klines = new JSONArray(response);
        List<MarketData> result = new ArrayList<>(klines.length());

        for (int i = 0; i < klines.length(); i++) {
            JSONArray kline = klines.getJSONArray(i);
            // [openTime, open, high, low, close, volume, closeTime, ...]
            long openTime = kline.getLong(0);

            result.add(MarketData.builder()
                    .pair(symbol)
                    .timeFrame(timeFrame)
                    .timestamp(Instant.ofEpochMilli(openTime))
                    .open(new BigDecimal(kline.getString(1)))
                    .high(new BigDecimal(kline.getString(2)))
                    .low(new BigDecimal(kline.getString(3)))
                    .close(new BigDecimal(kline.getString(4)))
                    .volume(new BigDecimal(kline.getString(5)))
                    .build());
        }

        return result.stream()
                .sorted(Comparator.comparing(MarketData::getTimestamp))
                .toList();
    }

    static String nativeInterval(TimeFrame timeFrame) {
        String interval = NATIVE_INTERVALS.get(timeFrame);
        if (interval == null) {
            throw new IllegalArgumentException(
                    "Binance market data client only supports TimeFrame.H1 natively, got: " + timeFrame);
        }
        return interval;
    }
}
