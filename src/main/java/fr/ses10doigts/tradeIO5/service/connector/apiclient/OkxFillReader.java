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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Historique des remplissages spot OKX : {@code GET /api/v5/trade/fills-history?instType=SPOT} (fenêtre OKX : 3 mois), pages de
 * 100 du plus récent au plus ancien ({@code after} = billId). Lecture seule, clé « Read ».
 */
@Component
public class OkxFillReader implements ExchangeFillReader {

    static final String PATH = "/api/v5/trade/fills-history?instType=SPOT&limit=100";
    private static final int MAX_PAGES = 30;

    private final DomainClock clock;

    public OkxFillReader(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        List<ImportedFill> out = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String body = OkxSignedRequests.signedGet(clock, credential, PATH + (after == null ? "" : "&after=" + after));
            JsonNode data = OkxSignedRequests.readData(body).path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            boolean reachedSince = false;
            for (JsonNode f : data) {
                ImportedFill fill = parse(f);
                if (fill == null) {
                    continue;
                }
                if (since != null && fill.ts().isBefore(since)) {
                    reachedSince = true;
                    continue;
                }
                out.add(fill);
                after = f.path("billId").asText(after);
            }
            if (reachedSince || data.size() < 100) {
                break;
            }
            after = data.get(data.size() - 1).path("billId").asText(null);
            if (after == null) {
                break;
            }
        }
        return out;
    }

    /** Package-private pour les tests. {@code null} si la ligne est inexploitable. */
    static ImportedFill parse(JsonNode f) {
        String instId = f.path("instId").asText("");
        String[] parts = instId.split("-");
        String tradeId = f.path("tradeId").asText("");
        if (parts.length != 2 || tradeId.isBlank()) {
            return null;
        }
        try {
            BigDecimal fee = f.path("fee").asText("").isBlank() ? BigDecimal.ZERO : new BigDecimal(f.path("fee").asText()).abs();
            return new ImportedFill(tradeId, f.path("ordId").asText(null), blankToNull(f.path("clOrdId").asText(null)),
                    parts[0].toUpperCase(), parts[1].toUpperCase(),
                    "sell".equalsIgnoreCase(f.path("side").asText()) ? TradeSide.SELL : TradeSide.BUY,
                    new BigDecimal(f.path("fillPx").asText()), new BigDecimal(f.path("fillSz").asText()), fee,
                    blankToNull(f.path("feeCcy").asText(null)), Instant.ofEpochMilli(Long.parseLong(f.path("ts").asText())));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("OKX : remplissage illisible (" + tradeId + ")", e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
