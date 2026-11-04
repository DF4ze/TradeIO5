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
 * Historique des remplissages spot Gate EU : {@code GET /spot/my_trades}. L'API borne la fenêtre à 30 jours (7 jours sans
 * {@code from}/{@code to}) : l'historique est lu fenêtre par fenêtre ({@code from}/{@code to} en secondes), pages de
 * {@link #PAGE_SIZE} par {@code page}. Frais lus en valeur absolue (coût positif) dans {@code fee_currency}. Lecture seule, clé
 * lecture.
 */
@Component
public class GateEuFillReader implements ExchangeFillReader {

    static final String TRADES_PATH = "/spot/my_trades";
    static final Instant EARLIEST = Instant.parse("2024-01-01T00:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(30);
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_CALLS = 400;

    private final DomainClock clock;

    public GateEuFillReader(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.GATE_EU;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        GateEuRequests.requireComplete(credential);
        Instant now = clock.now();
        Instant start = since == null || since.isBefore(EARLIEST) ? EARLIEST : since;
        List<ImportedFill> out = new ArrayList<>();
        int calls = 0;
        while (start.isBefore(now)) {
            Instant end = start.plus(WINDOW).isBefore(now) ? start.plus(WINDOW) : now;
            int page = 1;
            int received;
            do {
                if (++calls > MAX_CALLS) {
                    throw new BalanceUnavailableException("Gate EU : trop d'appels pour lire l'historique");
                }
                String path = TRADES_PATH + "?from=" + start.getEpochSecond() + "&to=" + end.getEpochSecond() + "&limit=" + PAGE_SIZE
                        + "&page=" + page++;
                JsonNode rows = GateEuRequests.requireJson(GateEuRequests.get(credential, path), "spot/my_trades");
                received = rows.isArray() ? rows.size() : 0;
                for (JsonNode row : rows) {
                    ImportedFill fill = parse(row);
                    if (fill != null) {
                        out.add(fill);
                    }
                }
            } while (received >= PAGE_SIZE);
            start = end;
        }
        return out;
    }

    /** Package-private pour les tests. {@code null} si la ligne est inexploitable. */
    static ImportedFill parse(JsonNode row) {
        String[] parts = row.path("currency_pair").asText("").split("_");
        String tradeId = row.path("id").asText("");
        if (parts.length != 2 || tradeId.isBlank()) {
            return null;
        }
        try {
            String feeText = row.path("fee").asText("");
            BigDecimal fee = feeText.isBlank() ? BigDecimal.ZERO : new BigDecimal(feeText).abs();
            String feeCcy = row.path("fee_currency").asText("");
            return new ImportedFill(tradeId, row.path("order_id").asText(null), null, parts[0].toUpperCase(), parts[1].toUpperCase(),
                    "sell".equalsIgnoreCase(row.path("side").asText()) ? TradeSide.SELL : TradeSide.BUY,
                    new BigDecimal(row.path("price").asText()), new BigDecimal(row.path("amount").asText()), fee,
                    feeCcy.isBlank() ? null : feeCcy.toUpperCase(), instantOf(row));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Gate EU : remplissage illisible (" + tradeId + ")", e);
        }
    }

    /** {@code create_time_ms} (millisecondes, éventuellement décimal), sinon {@code create_time} (secondes). */
    private static Instant instantOf(JsonNode row) {
        String ms = row.path("create_time_ms").asText("");
        if (!ms.isBlank()) {
            return Instant.ofEpochMilli(new BigDecimal(ms).longValue());
        }
        return Instant.ofEpochSecond(new BigDecimal(row.path("create_time").asText("0")).longValue());
    }
}
