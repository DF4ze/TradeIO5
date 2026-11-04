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
import java.util.regex.Pattern;

/**
 * Carnet public Bitvavo : {@code GET /{market}/book?depth=<n>} ; {@code bids} / {@code asks} = paires {@code [prix, quantité]} du
 * meilleur au moins bon. Pas de repli ticker : carnet indisponible ou vide => {@link InstrumentLookupException}. Aucune clé,
 * aucun ordre.
 */
@Component
public class BitvavoOrderBookClient implements OrderBookClient {

    private static final Pattern MARKET = Pattern.compile("[A-Z0-9]+-[A-Z0-9]+");

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.BITVAVO;
    }

    @Override
    public BookResult fetchBook(WebProvider provider, String instId, int depth) {
        if (!BitvavoRequests.usable(provider)) {
            throw new InstrumentLookupException("Bitvavo : provider non déclaré ou désactivé");
        }
        String market = instId.toUpperCase();
        if (!MARKET.matcher(market).matches()) {
            throw new InstrumentLookupException("Bitvavo : marché invalide " + instId);
        }
        try {
            BitvavoRequests.Reply reply = BitvavoRequests.publicGet(provider, "/" + market + "/book?depth=" + Math.max(depth, 1));
            if (!reply.ok()) {
                throw new InstrumentLookupException("Bitvavo : carnet en erreur HTTP " + reply.status());
            }
            return new BookResult(parse(reply.body()), PathEstimation.BOOK);
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
    }

    /** Package-private pour les tests. */
    static OrderBookSnapshot parse(String body) {
        JsonNode root = BitvavoRequests.readJson(body, "carnet");
        List<OrderBookLevel> bids = levels(root.path("bids"));
        List<OrderBookLevel> asks = levels(root.path("asks"));
        if (bids.isEmpty() || asks.isEmpty()) {
            throw new InstrumentLookupException("Bitvavo : carnet vide");
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
            throw new InstrumentLookupException("Bitvavo : niveau de carnet illisible", e);
        }
        return result;
    }
}
