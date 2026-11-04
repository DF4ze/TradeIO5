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
 * Carnet public Gate EU : {@code GET /spot/order_book?currency_pair=BTC_EUR&limit=<n>} ; {@code bids} (prix décroissant) et
 * {@code asks} (prix croissant) = listes {@code [prix, quantité]} en chaînes. Profondeur plafonnée à {@link #MAX_DEPTH} (au-delà
 * de 100 niveaux, Gate EU renvoie un carnet vide). Carnet indisponible ou vide => {@link InstrumentLookupException}. Aucune
 * clé, aucun ordre.
 */
@Component
public class GateEuOrderBookClient implements OrderBookClient {

    static final String BOOK_PATH = "/spot/order_book?currency_pair=";
    static final int MAX_DEPTH = 100;

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.GATE_EU;
    }

    @Override
    public BookResult fetchBook(WebProvider provider, String instId, int depth) {
        if (!GateEuRequests.usable(provider)) {
            throw new InstrumentLookupException("Gate EU : provider non déclaré ou désactivé");
        }
        String path = BOOK_PATH + GateEuRequests.nativePair(instId) + "&limit=" + Math.min(Math.max(depth, 1), MAX_DEPTH);
        try {
            GateEuRequests.Reply reply = GateEuRequests.publicGet(provider, path);
            if (!reply.ok()) {
                throw new InstrumentLookupException("Gate EU : carnet en erreur HTTP " + reply.status());
            }
            return new BookResult(parse(reply.body()), PathEstimation.BOOK);
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parse(String body) {
        JsonNode root = GateEuRequests.readJson(body, "carnet");
        List<OrderBookLevel> bids = levels(root.path("bids"));
        List<OrderBookLevel> asks = levels(root.path("asks"));
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new InstrumentLookupException("Gate EU : carnet vide");
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
                result.add(new OrderBookLevel(new BigDecimal(level.path(0).asText("")), new BigDecimal(level.path(1).asText(""))));
            }
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("Gate EU : niveau de carnet illisible", e);
        }
        return result;
    }
}
