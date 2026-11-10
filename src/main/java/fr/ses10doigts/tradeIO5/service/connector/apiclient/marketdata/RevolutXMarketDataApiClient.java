package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.RevolutXRequests;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.RevolutXRequests.Reply;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link MarketDataApiClient} Revolut X : {@code GET /1.0/public/candles/{symbol}} (sans clé, sans {@code ApiCredential}),
 * symbole {@code BASE-QUOTE} (cotation USDC : {@code BTC-USDC}, {@code ETH-USDC}). {@code interval} en minutes, {@code since}/{@code until}
 * en millisecondes. Pagination vers l'avant ({@link CandlePaginator}) à 1 requête/s (limite publique).
 */
@Component
public class RevolutXMarketDataApiClient implements MarketDataApiClient {

    private static final Logger logger = LoggerFactory.getLogger(RevolutXMarketDataApiClient.class);

    private static final String CANDLES_PATH = "/1.0/public/candles/";
    private static final Map<TimeFrame, Integer> NATIVE_INTERVAL_MINUTES = Map.of(TimeFrame.H1, 60, TimeFrame.D1, 1440);
    private static final long MINUTE_MILLIS = 60_000L;

    private final DomainClock clock;

    public RevolutXMarketDataApiClient(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public MarketDataSource getSource() {
        return MarketDataSource.REVOLUT_X;
    }

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        int minutes = nativeInterval(timeFrame);
        try {
            return CandlePaginator.paginate(since, until, limit, MarketDataProviderLimits.REVOLUT_X_CANDLES_PAGE_SIZE, timeFrame,
                    MarketDataProviderLimits.MAX_PAGES_PER_FETCH, MarketDataProviderLimits.REVOLUT_X_PAGE_PAUSE_MILLIS,
                    (start, pageLimit) -> fetchPage(symbol, timeFrame, minutes, start, until, pageLimit));
        } catch (MarketDataProviderException e) {
            logger.warn("Failed to fetch Revolut X candles for {} ({} min) : {}", symbol, minutes, e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.warn("Failed to fetch Revolut X candles for {} ({} min) : {}", symbol, minutes, e.getMessage());
            throw new ProviderUnavailableException(MarketDataSource.REVOLUT_X, symbol, e.getMessage(), e);
        }
    }

    private List<MarketData> fetchPage(String symbol, TimeFrame timeFrame, int minutes, Instant start, Instant until, int pageLimit) {
        long[] window = window(start, until, pageLimit, minutes, clock.now());
        String query = "interval=" + minutes + "&since=" + window[0] + "&until=" + window[1];
        Reply reply = RevolutXRequests.publicGet(RevolutXRequests.PUBLIC_ROOT, CANDLES_PATH + symbol, query);
        List<MarketData> candles = mapCandles(reply, symbol, timeFrame);
        return candles.size() > pageLimit ? candles.subList(0, pageLimit) : candles;
    }

    /**
     * Fenêtre {@code [since, until]} (ms) d'une page : au plus {@code pageLimit} bougies. Sans {@code start} on remonte de
     * {@code pageLimit} bougies avant {@code until} (ou avant {@code now}) ; sans {@code until} on s'arrête à {@code now}.
     */
    static long[] window(Instant start, Instant until, int pageLimit, int minutes, Instant now) {
        long span = pageLimit * minutes * MINUTE_MILLIS;
        long cap = until != null ? until.toEpochMilli() : now.toEpochMilli();
        long from = start != null ? start.toEpochMilli() : cap - span;
        long to = Math.min(cap, from + span);
        return new long[]{from, to};
    }

    /**
     * Mappe la réponse. 404/400 => symbole inconnu (permanent) ; 429, 5xx, JSON illisible, autre => indisponible (transitoire).
     * Isolée de l'appel réseau pour être testable.
     */
    static List<MarketData> mapCandles(Reply reply, String symbol, TimeFrame timeFrame) {
        if (reply.status() == 400 || reply.status() == 404) {
            throw new SymbolNotFoundException(MarketDataSource.REVOLUT_X, symbol,
                    "Revolut X HTTP " + reply.status() + " : " + RevolutXRequests.abbreviate(RevolutXRequests.errorOf(reply.body())));
        }
        if (!reply.ok()) {
            throw new ProviderUnavailableException(MarketDataSource.REVOLUT_X, symbol, "Revolut X HTTP " + reply.status(), null);
        }
        JsonNode data;
        try {
            data = RevolutXRequests.dataOf(RevolutXRequests.readJson(reply.body(), "candles"));
        } catch (RuntimeException e) {
            throw new ProviderUnavailableException(MarketDataSource.REVOLUT_X, symbol, e.getMessage(), e);
        }
        List<MarketData> candles = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode c : data) {
                candles.add(MarketData.builder().pair(symbol).timeFrame(timeFrame)
                        .timestamp(Instant.ofEpochMilli(c.get("start").asLong()))
                        .open(new BigDecimal(c.get("open").asText())).high(new BigDecimal(c.get("high").asText()))
                        .low(new BigDecimal(c.get("low").asText())).close(new BigDecimal(c.get("close").asText()))
                        .volume(new BigDecimal(c.get("volume").asText())).build());
            }
        }
        return candles.stream().sorted(Comparator.comparing(MarketData::getTimestamp)).toList();
    }

    static int nativeInterval(TimeFrame timeFrame) {
        Integer minutes = NATIVE_INTERVAL_MINUTES.get(timeFrame);
        if (minutes == null) {
            throw new IllegalArgumentException("Revolut X market data client only supports TimeFrame.H1 and TimeFrame.D1, got: " + timeFrame);
        }
        return minutes;
    }
}
