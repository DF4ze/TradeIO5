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
 * Historique des remplissages spot One Trading : {@code GET /account/trades}. L'API borne la fenêtre à 30 jours
 * ({@code from} ≥ 2024-01-01, {@code to} exclusif) : l'historique est lu fenêtre par fenêtre, pages de 100 par {@code cursor}.
 * Les trades n'exposent pas de {@code client_id} ({@code clOrdId} nul). Instruments hors spot ({@code BTC_EUR_P}) ignorés.
 * Lecture seule, clé « Read ».
 */
@Component
public class OneTradingFillReader implements ExchangeFillReader {

    static final String TRADES_PATH = "/account/trades";
    static final Instant EARLIEST = Instant.parse("2024-01-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final int PAGE_SIZE = 100;
    private static final int MAX_CALLS = 400;

    private final DomainClock clock;

    public OneTradingFillReader(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.ONE_TRADING;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        OneTradingRequests.requireComplete(credential);
        Instant now = clock.now();
        Instant start = since == null || since.isBefore(EARLIEST) ? EARLIEST : since;
        List<ImportedFill> out = new ArrayList<>();
        int calls = 0;
        while (start.isBefore(now)) {
            Instant end = start.plus(WINDOW).isBefore(now) ? start.plus(WINDOW) : now;
            String cursor = null;
            do {
                if (++calls > MAX_CALLS) {
                    throw new BalanceUnavailableException("One Trading : trop d'appels pour lire l'historique");
                }
                String path = TRADES_PATH + "?from=" + start + "&to=" + end + "&max_page_size=" + PAGE_SIZE + "&order=ASC"
                        + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode root = OneTradingRequests.requireJson(OneTradingRequests.get(credential, path), "account/trades");
                for (JsonNode row : root.path("trade_history")) {
                    ImportedFill fill = parse(row);
                    if (fill != null) {
                        out.add(fill);
                    }
                }
                cursor = root.path("cursor").isTextual() && !root.path("cursor").asText().isBlank() ? root.path("cursor").asText() : null;
            } while (cursor != null);
            start = end;
        }
        return out;
    }

    /** Package-private pour les tests. {@code null} si la ligne est inexploitable (ex. future). */
    static ImportedFill parse(JsonNode row) {
        JsonNode trade = row.path("trade");
        String[] parts = trade.path("instrument_code").asText("").split("_");
        String tradeId = trade.path("trade_id").asText("");
        if (parts.length != 2 || tradeId.isBlank()) {
            return null;
        }
        try {
            BigDecimal fee = row.path("fee").path("fee_amount").asText("").isBlank() ? BigDecimal.ZERO
                    : new BigDecimal(row.path("fee").path("fee_amount").asText()).abs();
            String feeCcy = row.path("fee").path("fee_currency").asText("");
            return new ImportedFill(tradeId, trade.path("order_id").asText(null), null, parts[0].toUpperCase(), parts[1].toUpperCase(),
                    "SELL".equalsIgnoreCase(trade.path("side").asText()) ? TradeSide.SELL : TradeSide.BUY,
                    new BigDecimal(trade.path("price").asText()), new BigDecimal(trade.path("amount").asText()), fee,
                    feeCcy.isBlank() ? null : feeCcy, Instant.parse(trade.path("time").asText()));
        } catch (NumberFormatException | java.time.format.DateTimeParseException e) {
            throw new BalanceUnavailableException("One Trading : remplissage illisible (" + tradeId + ")", e);
        }
    }
}
