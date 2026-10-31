package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.trades.ExchangeFillReader;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Historique des trades Kraken : {@code POST /0/private/TradesHistory}, pages de 50 ({@code ofs}), lecture seule (permission
 * « Query closed orders & trades »). Le <b>nonce</b> est sérialisé par clé dans {@link KrakenSignedRequests}.
 */
@Component
public class KrakenFillReader implements ExchangeFillReader {

    private static final int PAGE = 50;
    private static final int MAX_PAGES = 40;
    /** Du plus long au plus court : {@code XBTUSDC -> USDC}, {@code XXBTZUSD -> ZUSD}. */
    private static final List<String> QUOTES = List.of("USDC", "USDT", "ZUSD", "USD", "ZEUR", "EUR", "ZGBP", "GBP", "XXBT", "XBT", "XETH", "ETH");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DomainClock clock;

    public KrakenFillReader(DomainClock clock) {
        this.clock = clock;
    }

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.KRAKEN;
    }

    @Override
    public List<ImportedFill> fetchFills(ApiCredential credential, Instant since) {
        List<ImportedFill> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> params = new java.util.LinkedHashMap<>();
            params.put("ofs", String.valueOf(offset));
            if (since != null) {
                params.put("start", String.valueOf(since.getEpochSecond()));
            }
            JsonNode response;
            try {
                response = MAPPER.readTree(KrakenSignedRequests.privatePost(clock, credential, "TradesHistory", params));
            } catch (BalanceUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new BalanceUnavailableException("Kraken : réponse TradesHistory illisible", e);
            }
            JsonNode errors = response.path("error");
            if (errors.isArray() && !errors.isEmpty()) {
                String message = "Kraken : erreur API " + errors;
                throw errors.toString().matches(".*(Invalid key|Invalid signature|Permission denied).*")
                        ? new CredentialRejectedException(message) : new BalanceUnavailableException(message);
            }
            JsonNode trades = response.path("result").path("trades");
            int count = response.path("result").path("count").asInt(0);
            int seen = 0;
            for (Map.Entry<String, JsonNode> e : trades.properties()) {
                seen++;
                ImportedFill fill = parse(e.getKey(), e.getValue());
                if (fill != null) {
                    out.add(fill);
                }
            }
            offset += seen;
            if (seen == 0 || offset >= count) {
                break;
            }
        }
        return out;
    }

    /** Package-private pour les tests. {@code null} si la paire n'est pas décomposable (base/cotation inconnues). */
    static ImportedFill parse(String txid, JsonNode t) {
        // certaines paires arrivent séparées (« PAXG/USDC ») : le séparateur ne fait jamais partie du symbole
        String pair = t.path("pair").asText("").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9.]", "");
        String quoteRaw = QUOTES.stream().filter(q -> pair.endsWith(q) && pair.length() > q.length()).findFirst().orElse(null);
        if (quoteRaw == null) {
            return null;
        }
        Optional<String> base = KrakenAssetNames.toSymbol(pair.substring(0, pair.length() - quoteRaw.length()));
        Optional<String> quote = KrakenAssetNames.toSymbol(quoteRaw);
        if (base.isEmpty() || quote.isEmpty()) {
            return null;
        }
        try {
            // frais Kraken : exprimés dans la devise de cotation
            return new ImportedFill(txid, t.path("ordertxid").asText(null), blankToNull(t.path("cl_ord_id").asText(null)), base.get(), quote.get(),
                    "sell".equalsIgnoreCase(t.path("type").asText()) ? TradeSide.SELL : TradeSide.BUY,
                    new BigDecimal(t.path("price").asText()), new BigDecimal(t.path("vol").asText()),
                    new BigDecimal(t.path("fee").asText("0")), quote.get(),
                    Instant.ofEpochMilli((long) (t.path("time").asDouble() * 1000)));
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Kraken : trade illisible (" + txid + ")", e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
