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
 * Carnet public One Trading : {@code GET /order-book/{instrument_code}?level=1&depth=<n>} ({@code depth} l'emporte sur
 * {@code level}) ; {@code bids} / {@code asks} = objets {@code {price, amount}} du meilleur au moins bon. Pas de repli ticker
 * (page ticker non lue en doc) : carnet indisponible ou vide => {@link InstrumentLookupException}. Aucune clé, aucun ordre.
 */
@Component
public class OneTradingOrderBookClient implements OrderBookClient {

    static final String BOOK_PATH = "/order-book/";

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.ONE_TRADING;
    }

    @Override
    public BookResult fetchBook(WebProvider provider, String instId, int depth) {
        if (!OneTradingRequests.usable(provider)) {
            throw new InstrumentLookupException("One Trading : provider non déclaré ou désactivé");
        }
        String path = BOOK_PATH + OneTradingRequests.nativeCode(instId) + "?level=1&depth=" + Math.max(depth, 1);
        try {
            OneTradingRequests.Reply reply = OneTradingRequests.publicGet(provider, path);
            if (!reply.ok()) {
                throw new InstrumentLookupException("One Trading : carnet en erreur HTTP " + reply.status());
            }
            return new BookResult(parse(reply.body()), PathEstimation.BOOK);
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parse(String body) {
        JsonNode root = OneTradingRequests.readJson(body, "carnet");
        List<OrderBookLevel> bids = levels(root.path("bids"));
        List<OrderBookLevel> asks = levels(root.path("asks"));
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new InstrumentLookupException("One Trading : carnet vide");
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
                result.add(new OrderBookLevel(new BigDecimal(level.path("price").asText("")), new BigDecimal(level.path("amount").asText(""))));
            }
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("One Trading : niveau de carnet illisible", e);
        }
        return result;
    }
}
