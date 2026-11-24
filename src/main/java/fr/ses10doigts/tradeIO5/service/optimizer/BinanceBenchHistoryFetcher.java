package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Bougies D1 Binance en USDT (API publique, aucune clé), pour l'étude / le bench uniquement : mêmes règles que
 * {@code tools/bench-data/fetch_binance_d1.py} (pages de 1 000, pause entre pages, séquentiel, bougie du jour non close écartée). Tout échec
 * (réseau, HTTP, JSON, actif absent) ⇒ {@link Optional#empty()} + WARN, jamais d'exception.
 */
@Slf4j
@Component
public class BinanceBenchHistoryFetcher implements BenchHistoryFetcher {

    static final String QUOTE = "USDT";
    private static final String URL = "https://api.binance.com/api/v3/klines?symbol=%s&interval=1d&limit=1000&startTime=%d";
    private static final long START_MS = Instant.parse("2017-01-01T00:00:00Z").toEpochMilli();
    private static final long DAY_MS = 86_400_000L;
    private static final int MAX_PAGES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final long pauseMs = NewAssetConfig.defaults().binancePauseMs();

    @Override
    public Optional<Fetched> fetch(String asset) {
        String symbol = asset + QUOTE;
        try {
            TreeMap<Long, MarketData> byTs = new TreeMap<>();
            long nowMs = System.currentTimeMillis();
            long start = START_MS;
            for (int page = 0; page < MAX_PAGES; page++) {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(String.format(URL, symbol, start))).timeout(Duration.ofSeconds(30))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    log.warn("Binance bench {} : HTTP {}", symbol, r.statusCode());
                    return Optional.empty();
                }
                JsonNode rows = MAPPER.readTree(r.body());
                if (!rows.isArray()) {
                    return Optional.empty();
                }
                for (JsonNode k : rows) {
                    long ts = k.get(0).asLong();
                    if (k.get(6).asLong() >= nowMs) {
                        continue; // bougie du jour non close
                    }
                    byTs.putIfAbsent(ts, MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochMilli(ts)).pair(symbol)
                            .open(new BigDecimal(k.get(1).asText())).high(new BigDecimal(k.get(2).asText())).low(new BigDecimal(k.get(3).asText()))
                            .close(new BigDecimal(k.get(4).asText())).volume(new BigDecimal(k.get(5).asText())).build());
                }
                if (rows.size() < 1000) {
                    break;
                }
                start = rows.get(rows.size() - 1).get(0).asLong() + DAY_MS;
                Thread.sleep(pauseMs);
            }
            return byTs.isEmpty() ? Optional.empty() : Optional.of(new Fetched(symbol, QUOTE, new ArrayList<>(byTs.values())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Binance bench {} injoignable : {}", symbol, e.toString());
            return Optional.empty();
        }
    }
}
