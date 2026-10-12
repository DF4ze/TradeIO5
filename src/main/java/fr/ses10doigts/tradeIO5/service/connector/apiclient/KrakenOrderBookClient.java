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
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Carnet public Kraken : {@code GET /0/public/Depth?pair=<nativePair>&count=<n>} (niveaux {@code [prix, volume, horodatage]}
 * triés du meilleur au moins bon). Repli : {@code GET /0/public/Ticker} (meilleur bid/ask, un niveau par côté) marqué
 * {@link PathEstimation#TICKER}. La paire est lue au catalogue ({@code nativePair}), repli {@link KrakenAssetNames#fallbackPair}.
 * Aucune clé, aucun ordre.
 */
@Slf4j
@Component
public class KrakenOrderBookClient implements OrderBookClient {

    static final String DEPTH_PATH = "/0/public/Depth";
    static final String TICKER_PATH = "/0/public/Ticker";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InstrumentCatalog catalog;

    public KrakenOrderBookClient(InstrumentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.KRAKEN;
    }

    @Override
    public BookResult fetchBook(WebProvider provider, String instId, int depth) {
        if (provider == null || provider.getApiBaseUrl() == null || provider.getApiBaseUrl().isBlank()) {
            throw new InstrumentLookupException("Kraken : URL de l'API absente");
        }
        String pair = catalog.nativePair(WebProviderCode.KRAKEN, instId).orElseGet(() -> KrakenAssetNames.fallbackPair(instId));
        try {
            return new BookResult(parseDepth(get(provider, DEPTH_PATH, pair, depth)), PathEstimation.BOOK);
        } catch (InstrumentLookupException e) {
            log.warn("Kraken : carnet indisponible pour {} ({}), repli sur le ticker", instId, e.getMessage());
        }
        return new BookResult(parseTicker(get(provider, TICKER_PATH, pair, 0)), PathEstimation.TICKER);
    }

    private static String get(WebProvider provider, String path, String pair, int depth) {
        try {
            return WebClient.builder().baseUrl(provider.getApiBaseUrl()).build()
                    .get()
                    .uri(uri -> {
                        var builder = uri.path(path).queryParam("pair", pair);
                        return (depth > 0 ? builder.queryParam("count", depth) : builder).build();
                    })
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new InstrumentLookupException("Kraken : appel " + path + " impossible (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parseDepth(String body) {
        JsonNode data = readFirstResult(body, "Depth");
        List<OrderBookLevel> bids = levels(data.path("bids"));
        List<OrderBookLevel> asks = levels(data.path("asks"));
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new InstrumentLookupException("Kraken : carnet vide");
        }
        return new OrderBookSnapshot(bids, asks);
    }

    /** Package-private pour les tests : {@code a}/{@code b} = {@code [prix, volume entier, volume]}. */
    static OrderBookSnapshot parseTicker(String body) {
        JsonNode data = readFirstResult(body, "Ticker");
        try {
            OrderBookLevel bid = new OrderBookLevel(new BigDecimal(data.path("b").path(0).asText("")),
                    new BigDecimal(data.path("b").path(2).asText("0")));
            OrderBookLevel ask = new OrderBookLevel(new BigDecimal(data.path("a").path(0).asText("")),
                    new BigDecimal(data.path("a").path(2).asText("0")));
            return new OrderBookSnapshot(List.of(bid), List.of(ask));
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("Kraken : ticker illisible", e);
        }
    }

    private static JsonNode readFirstResult(String body, String what) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("Kraken : réponse " + what + " illisible", e);
        }
        if (root == null || !root.isObject()) {
            throw new InstrumentLookupException("Kraken : réponse " + what + " inattendue");
        }
        JsonNode errors = root.path("error");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new InstrumentLookupException("Kraken : erreur API " + what + " " + errors);
        }
        Iterator<JsonNode> values = root.path("result").isObject() ? root.path("result").elements() : null;
        if (values == null || !values.hasNext()) {
            throw new InstrumentLookupException("Kraken : réponse " + what + " vide");
        }
        return values.next();
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
            throw new InstrumentLookupException("Kraken : niveau de carnet illisible", e);
        }
        return result;
    }
}
