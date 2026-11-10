package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.trades.ExchangeFillReader;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Historique des remplissages spot Revolut X, en lecture seule (clé « Read »). Il n'existe pas d'historique de trades sans
 * symbole : {@code GET /1.0/orders/historical} (états {@code filled} et {@code cancelled}, fenêtres de 30 jours au plus, pages
 * par {@code cursor}) donne les ordres, puis pour chaque ordre exécuté le détail ({@code GET /1.0/orders/{id}} : frais) et les
 * remplissages ({@code GET /1.0/orders/fills/{id}}). L'identifiant de trade est le {@code tid} (celui du client d'ordres). Sans
 * date de départ, l'import couvre {@link #DEFAULT_LOOKBACK}. Le frais total de l'ordre est porté par son premier remplissage.
 */
@Component
public class RevolutXFillReader implements ExchangeFillReader {

    static final String HISTORICAL_PATH = "/1.0/orders/historical";
    static final String ORDER_PATH = "/1.0/orders/";
    static final String FILLS_PATH = "/1.0/orders/fills/";
    static final Duration DEFAULT_LOOKBACK = Duration.ofDays(365);
    private static final Duration WINDOW = Duration.ofDays(30);
    /** {@code orders/historical} coûte 1 jeton par jour de plage, plafonné à 100 jetons/s : pause entre deux fenêtres (par jour de plage). */
    private static final long PAUSE_MILLIS_PER_DAY = 12;
    private static final int PAGE_SIZE = 1900;
    private static final int MAX_CALLS = 600;
    /** Détail de l'ordre + remplissages. */
    private static final int CALLS_PER_ORDER = 2;

    private final DomainClock clock;

    public RevolutXFillReader(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        RevolutXRequests.requireComplete(credential);
        Instant now = clock.now();
        Instant start = since == null ? now.minus(DEFAULT_LOOKBACK) : since;
        List<ImportedFill> out = new ArrayList<>();
        int calls = 0;
        while (start.isBefore(now)) {
            Instant end = start.plus(WINDOW).isBefore(now) ? start.plus(WINDOW) : now;
            String cursor = null;
            do {
                String query = "order_states=filled,cancelled&start_date=" + start.toEpochMilli() + "&end_date=" + end.toEpochMilli()
                        + "&limit=" + PAGE_SIZE + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode root = RevolutXRequests.requireJson(RevolutXRequests.getWithRetry(credential, HISTORICAL_PATH, query), "orders/historical");
                pause(Duration.between(start, end).toDays() * PAUSE_MILLIS_PER_DAY);
                calls++;
                for (JsonNode order : RevolutXRequests.dataOf(root)) {
                    if (decimalOrZero(order, "filled_quantity").signum() <= 0) {
                        continue;
                    }
                    calls += CALLS_PER_ORDER;
                    if (calls > MAX_CALLS) {
                        throw new BalanceUnavailableException("Revolut X : trop d'appels pour lire l'historique");
                    }
                    out.addAll(fillsOf(credential, order));
                }
                String next = root.path("metadata").path("next_cursor").asText("");
                cursor = next.isBlank() ? null : next;
            } while (cursor != null);
            start = end;
        }
        return out;
    }

    private List<ImportedFill> fillsOf(ApiCredential credential, JsonNode order) {
        String id = order.path("id").asText("");
        if (id.isBlank() || !id.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
            throw new BalanceUnavailableException("Revolut X : identifiant d'ordre illisible");
        }
        JsonNode details = RevolutXRequests.dataOf(RevolutXRequests.requireJson(RevolutXRequests.getWithRetry(credential, ORDER_PATH + id, null),
                "orders/{id}")).path(0);
        BigDecimal fee = RevolutXTrades.totalFee(details);
        String feeCcy = details.path("fee_currency").asText("");
        List<ImportedFill> out = new ArrayList<>();
        boolean first = true;
        for (RevolutXTrades.Trade trade : RevolutXTrades.parse(RevolutXRequests.dataOf(
                RevolutXRequests.requireJson(RevolutXRequests.getWithRetry(credential, FILLS_PATH + id, null), "orders/fills")))) {
            String side = trade.side().isBlank() ? order.path("side").asText() : trade.side();
            out.add(new ImportedFill(trade.tradeId(), id, order.path("client_order_id").asText(null), trade.base(), trade.quote(),
                    "sell".equalsIgnoreCase(side) ? TradeSide.SELL : TradeSide.BUY, trade.price(), trade.quantity(),
                    first ? fee : BigDecimal.ZERO, feeCcy.isBlank() ? null : feeCcy, trade.time()));
            first = false;
        }
        return out;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static BigDecimal decimalOrZero(JsonNode node, String field) {
        try {
            return new BigDecimal(node.path(field).asText("0"));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Revolut X : champ " + field + " illisible", e);
        }
    }
}
