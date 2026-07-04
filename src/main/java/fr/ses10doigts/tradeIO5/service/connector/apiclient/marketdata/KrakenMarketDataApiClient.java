package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implémentation {@link MarketDataApiClient} pour Kraken.
 * <p>
 * Réutilise le pattern WebClient déjà écrit dans
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.KrakenApiClient} (méthode
 * {@code publicGet}), mais sans le paramètre {@code ApiCredential} : ce dernier n'y servait
 * qu'à résoudre l'URL de base, remplacée ici par la constante publique {@link #API_URL}.
 */
@Component
public class KrakenMarketDataApiClient implements MarketDataApiClient {

    private static final Logger logger = LoggerFactory.getLogger(KrakenMarketDataApiClient.class);

    // Équivalent de KrakenApiClient.API_URL, mais sans dépendre d'un ApiCredential
    private static final String API_URL = "https://api.kraken.com";
    private static final String API_VERSION = "0";

    // Seul TimeFrame ingéré nativement par Bucket aujourd'hui (cf. Bucket.BASE_TIME_FRAME)
    // Kraken exprime son intervalle OHLC en minutes.
    private static final Map<TimeFrame, Integer> NATIVE_INTERVALS = Map.of(TimeFrame.H1, 60);

    private final WebClient webClient;

    public KrakenMarketDataApiClient() {
        this.webClient = WebClient.builder().baseUrl(API_URL).build();
    }

    @Override
    public MarketDataSource getSource() {
        return MarketDataSource.KRAKEN;
    }

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        int interval = nativeInterval(timeFrame);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromPath("/" + API_VERSION + "/public/OHLC")
                .queryParam("pair", symbol)
                .queryParam("interval", interval);
        if (since != null) {
            uriBuilder.queryParam("since", since.getEpochSecond());
        }

        try {
            String body = webClient.get()
                    .uri(uriBuilder.toUriString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<MarketData> candles = mapOhlcResponse(body, symbol, timeFrame);

            if (until != null) {
                candles = candles.stream()
                        .filter(c -> !c.getTimestamp().isAfter(until))
                        .toList();
            }
            if (limit > 0 && candles.size() > limit) {
                candles = candles.subList(candles.size() - limit, candles.size());
            }
            return candles;
        } catch (Exception e) {
            logger.warn("Failed to fetch Kraken OHLC for {} ({}) : {}", symbol, interval, e.getMessage());
        }
        return List.of();
    }

    /**
     * Mappe le JSON {@code result.<pair>} (tableaux [time, open, high, low, close, vwap, volume, count])
     * vers une liste de {@link MarketData}. Isolée de l'appel réseau pour être testable en unitaire.
     */
    static List<MarketData> mapOhlcResponse(String body, String symbol, TimeFrame timeFrame) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(body);

        if (response.has("error") && !response.get("error").isEmpty()) {
            throw new IllegalStateException("Kraken API error: " + response.get("error"));
        }

        JsonNode result = response.get("result");
        List<MarketData> candles = new ArrayList<>();
        if (result == null) {
            return candles;
        }

        // La clé du pair renvoyée par Kraken peut différer du symbole demandé
        // (ex: "XBTUSD" -> "XXBTZUSD"). On prend la première entrée qui n'est pas "last".
        JsonNode series = null;
        Iterator<Map.Entry<String, JsonNode>> fields = result.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!"last".equals(entry.getKey())) {
                series = entry.getValue();
                break;
            }
        }
        if (series == null) {
            return candles;
        }

        for (JsonNode candle : series) {
            // [time, open, high, low, close, vwap, volume, count]
            long time = candle.get(0).asLong();

            candles.add(MarketData.builder()
                    .pair(symbol)
                    .timeFrame(timeFrame)
                    .timestamp(Instant.ofEpochSecond(time))
                    .open(new BigDecimal(candle.get(1).asText()))
                    .high(new BigDecimal(candle.get(2).asText()))
                    .low(new BigDecimal(candle.get(3).asText()))
                    .close(new BigDecimal(candle.get(4).asText()))
                    .volume(new BigDecimal(candle.get(6).asText()))
                    .build());
        }

        return candles.stream()
                .sorted(Comparator.comparing(MarketData::getTimestamp))
                .toList();
    }

    static int nativeInterval(TimeFrame timeFrame) {
        Integer interval = NATIVE_INTERVALS.get(timeFrame);
        if (interval == null) {
            throw new IllegalArgumentException(
                    "Kraken market data client only supports TimeFrame.H1 natively, got: " + timeFrame);
        }
        return interval;
    }
}
