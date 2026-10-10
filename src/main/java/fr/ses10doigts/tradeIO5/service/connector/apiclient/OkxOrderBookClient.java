package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Carnet public OKX : {@code GET /api/v5/market/books?instId=...&sz=...} (niveaux triés du meilleur au moins bon).
 * Repli : {@code GET /api/v5/market/ticker} (meilleur bid/ask, un niveau par côté) marqué {@link PathEstimation#TICKER}.
 */
@Slf4j
@Component
public class OkxOrderBookClient implements OrderBookClient {

    static final String BOOKS_PATH = "/api/v5/market/books";
    static final String TICKER_PATH = "/api/v5/market/ticker";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public BookResult fetchBook(WebProvider provider, String instId, int depth) {
        if (provider == null || provider.getApiBaseUrl() == null || provider.getApiBaseUrl().isBlank()) {
            throw new InstrumentLookupException("OKX : URL de l'API absente");
        }
        try {
            OrderBookSnapshot book = parseBook(get(provider, BOOKS_PATH, instId, depth));
            return new BookResult(book, PathEstimation.BOOK);
        } catch (InstrumentLookupException e) {
            log.warn("OKX : carnet indisponible pour {} ({}), repli sur le ticker", instId, e.getMessage());
        }
        OrderBookSnapshot ticker = parseTicker(get(provider, TICKER_PATH, instId, 0));
        return new BookResult(ticker, PathEstimation.TICKER);
    }

    private static String get(WebProvider provider, String path, String instId, int depth) {
        try {
            return WebClient.builder().baseUrl(provider.getApiBaseUrl()).build()
                    .get()
                    .uri(uri -> {
                        var builder = uri.path(path).queryParam("instId", instId);
                        return (depth > 0 ? builder.queryParam("sz", depth) : builder).build();
                    })
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new InstrumentLookupException("OKX : appel " + path + " impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parseBook(String body) {
        JsonNode data = readData(body, "books");
        List<OrderBookLevel> bids = levels(data.path("bids"));
        List<OrderBookLevel> asks = levels(data.path("asks"));
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new InstrumentLookupException("OKX : carnet vide");
        }
        return new OrderBookSnapshot(bids, asks);
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parseTicker(String body) {
        JsonNode data = readData(body, "ticker");
        try {
            OrderBookLevel bid = new OrderBookLevel(new BigDecimal(data.path("bidPx").asText("")),
                    new BigDecimal(data.path("bidSz").asText("0")));
            OrderBookLevel ask = new OrderBookLevel(new BigDecimal(data.path("askPx").asText("")),
                    new BigDecimal(data.path("askSz").asText("0")));
            return new OrderBookSnapshot(List.of(bid), List.of(ask));
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("OKX : ticker illisible", e);
        }
    }

    private static JsonNode readData(String body, String what) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("OKX : réponse " + what + " illisible", e);
        }
        if (root == null || !root.isObject()) {
            throw new InstrumentLookupException("OKX : réponse " + what + " inattendue");
        }
        String code = root.path("code").asText("");
        if (!OkxSignedRequests.OK_CODE.equals(code)) {
            throw new InstrumentLookupException("OKX : erreur API " + what + " code=" + code);
        }
        JsonNode first = root.path("data").path(0);
        if (!first.isObject()) {
            throw new InstrumentLookupException("OKX : réponse " + what + " vide");
        }
        return first;
    }

    private static List<OrderBookLevel> levels(JsonNode array) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }
        try {
            for (JsonNode level : array) {
                result.add(new OrderBookLevel(new BigDecimal(level.path(0).asText("")), new BigDecimal(level.path(1).asText(""))));
            }
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("OKX : niveau de carnet illisible", e);
        }
        return result;
    }
}
