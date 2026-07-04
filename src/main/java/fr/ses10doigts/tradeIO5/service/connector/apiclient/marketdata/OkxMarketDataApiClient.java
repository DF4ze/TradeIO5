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
import java.util.List;
import java.util.Map;

/**
 * Implémentation {@link MarketDataApiClient} pour OKX.
 * <p>
 * Endpoint public GET /api/v5/market/candles : aucune signature requise, contrairement aux
 * endpoints privés (balances, ordres) qui nécessiteraient un {@code ApiCredential}.
 */
@Component
public class OkxMarketDataApiClient implements MarketDataApiClient {

    private static final Logger logger = LoggerFactory.getLogger(OkxMarketDataApiClient.class);

    private static final String API_URL = "https://www.okx.com";

    // Seul TimeFrame ingéré nativement par Bucket aujourd'hui (cf. Bucket.BASE_TIME_FRAME)
    private static final Map<TimeFrame, String> NATIVE_BARS = Map.of(TimeFrame.H1, "1H");

    private final WebClient webClient;

    public OkxMarketDataApiClient() {
        this.webClient = WebClient.builder().baseUrl(API_URL).build();
    }

    @Override
    public MarketDataSource getSource() {
        return MarketDataSource.OKX;
    }

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        String bar = nativeBar(timeFrame);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
                .fromPath("/api/v5/market/candles")
                .queryParam("instId", symbol)
                .queryParam("bar", bar)
                .queryParam("limit", limit > 0 ? limit : 100);
        if (until != null) {
            uriBuilder.queryParam("after", until.toEpochMilli());
        }
        if (since != null) {
            uriBuilder.queryParam("before", since.toEpochMilli());
        }

        try {
            String body = webClient.get()
                    .uri(uriBuilder.toUriString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return mapCandlesResponse(body, symbol, timeFrame);
        } catch (Exception e) {
            logger.warn("Failed to fetch OKX candles for {} ({}) : {}", symbol, bar, e.getMessage());
        }
        return List.of();
    }

    /**
     * Mappe le tableau OKX ([ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]) vers une liste
     * de {@link MarketData}. Isolée de l'appel réseau pour être testable en unitaire.
     */
    static List<MarketData> mapCandlesResponse(String body, String symbol, TimeFrame timeFrame) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode response = mapper.readTree(body);

        if (response.has("code") && !"0".equals(response.get("code").asText())) {
            throw new IllegalStateException("OKX API error: " + response.path("msg").asText());
        }

        List<MarketData> candles = new ArrayList<>();
        JsonNode data = response.get("data");
        if (data == null) {
            return candles;
        }

        for (JsonNode candle : data) {
            // [ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]
            long ts = candle.get(0).asLong();

            candles.add(MarketData.builder()
                    .pair(symbol)
                    .timeFrame(timeFrame)
                    .timestamp(Instant.ofEpochMilli(ts))
                    .open(new BigDecimal(candle.get(1).asText()))
                    .high(new BigDecimal(candle.get(2).asText()))
                    .low(new BigDecimal(candle.get(3).asText()))
                    .close(new BigDecimal(candle.get(4).asText()))
                    .volume(new BigDecimal(candle.get(5).asText()))
                    .build());
        }

        // OKX renvoie les bougies du plus récent au plus ancien : on remet en ordre chronologique
        return candles.stream()
                .sorted(Comparator.comparing(MarketData::getTimestamp))
                .toList();
    }

    static String nativeBar(TimeFrame timeFrame) {
        String bar = NATIVE_BARS.get(timeFrame);
        if (bar == null) {
            throw new IllegalArgumentException(
                    "OKX market data client only supports TimeFrame.H1 natively, got: " + timeFrame);
        }
        return bar;
    }
}
