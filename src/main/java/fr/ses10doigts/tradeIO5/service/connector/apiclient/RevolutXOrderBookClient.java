package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Carnet public Revolut X : {@code GET /2.0/public/order-book/{symbol}?limit=<n>&region=EEA} ({@code limit} 1 à 192). Le schéma
 * de la réponse v2 n'est pas détaillé par la doc : la lecture accepte la forme du carnet authentifié ({@code data.asks/bids},
 * niveaux {@code p} / {@code q}) et les noms longs ({@code price} / {@code quantity|amount}). Carnet indisponible ou vide =>
 * {@link InstrumentLookupException}. Aucune clé, aucun ordre.
 */
@Component
public class RevolutXOrderBookClient implements OrderBookClient {

    static final String BOOK_PATH = "/2.0/public/order-book/";
    static final int MAX_LEVELS = 192;

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public BookResult fetchBook(WebProvider provider, String instId, int depth) {
        if (!RevolutXRequests.usable(provider)) {
            throw new InstrumentLookupException("Revolut X : provider non déclaré ou désactivé");
        }
        String query = "limit=" + Math.min(Math.max(depth, 1), MAX_LEVELS) + "&" + RevolutXInstrumentListClient.REGION_QUERY;
        try {
            RevolutXRequests.Reply reply = RevolutXRequests.publicGet(provider, BOOK_PATH + instId.toUpperCase(), query);
            if (!reply.ok()) {
                throw new InstrumentLookupException("Revolut X : carnet en erreur HTTP " + reply.status());
            }
            return new BookResult(parse(reply.body()), PathEstimation.BOOK);
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parse(String body) {
        JsonNode root = RevolutXRequests.readJson(body, "carnet");
        JsonNode book = root.path("data").isObject() ? root.path("data") : root;
        List<OrderBookLevel> bids = levels(book.path("bids"));
        List<OrderBookLevel> asks = levels(book.path("asks"));
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new InstrumentLookupException("Revolut X : carnet vide");
        }
        return new OrderBookSnapshot(bids, asks);
    }

    private static List<OrderBookLevel> levels(JsonNode array) {
        List<OrderBookLevel> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }
        try {
            for (JsonNode level : array) {
                result.add(new OrderBookLevel(new BigDecimal(first(level, "p", "price")), new BigDecimal(first(level, "q", "quantity", "amount"))));
            }
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("Revolut X : niveau de carnet illisible", e);
        }
        return result;
    }

    private static String first(JsonNode node, String... names) {
        for (String name : names) {
            if (node.path(name).isTextual() || node.path(name).isNumber()) {
                return node.path(name).asText();
            }
        }
        return "";
    }
}
