package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Toutes les paires spot Gate EU en un appel public : {@code GET /spot/currency_pairs}. Négociable si
 * {@code trade_status = tradable}. {@code lotSz = 10^-amount_precision}, {@code tickSz = 10^-precision}, {@code minSz =
 * min_base_amount} (sinon le lot), {@code costMin = min_quote_amount}, {@code nativePair} = identifiant Gate
 * ({@code BTC_EUR}). Le provider doit être déclaré et actif.
 */
@Slf4j
@Component
public class GateEuInstrumentListClient implements InstrumentListClient {

    static final String PAIRS_PATH = "/spot/currency_pairs";

    private static final String TRADABLE = "tradable";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.GATE_EU;
    }

    @Override
    public List<Listed> fetchAll(WebProvider provider) {
        if (!GateEuRequests.usable(provider)) {
            throw new InstrumentLookupException("Gate EU : provider non déclaré ou désactivé");
        }
        String body;
        try {
            GateEuRequests.Reply reply = GateEuRequests.publicGet(provider, PAIRS_PATH);
            if (!reply.ok()) {
                throw new InstrumentLookupException("Gate EU : paires en erreur HTTP " + reply.status());
            }
            body = reply.body();
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
        List<Listed> listed = parse(body);
        log.debug("Gate EU : {} paires spot lues", listed.size());
        return listed;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("Gate EU : réponse paires illisible", e);
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new InstrumentLookupException("Gate EU : liste de paires vide ou inattendue");
        }
        List<Listed> out = new ArrayList<>();
        for (JsonNode item : root) {
            Listed listed = toListed(item);
            if (listed != null) {
                out.add(listed);
            }
        }
        if (out.isEmpty()) {
            throw new InstrumentLookupException("Gate EU : aucune paire spot exploitable");
        }
        return out;
    }

    private static Listed toListed(JsonNode item) {
        String base = item.path("base").asText("").toUpperCase();
        String quote = item.path("quote").asText("").toUpperCase();
        if (base.isBlank() || quote.isBlank() || !item.path("amount_precision").canConvertToInt()
                || !item.path("precision").canConvertToInt()) {
            return null;
        }
        try {
            BigDecimal lot = BigDecimal.ONE.movePointLeft(item.path("amount_precision").asInt());
            BigDecimal tick = BigDecimal.ONE.movePointLeft(item.path("precision").asInt());
            BigDecimal minBase = decimalOrNull(item, "min_base_amount");
            return new Listed(base + "-" + quote, base, quote, TRADABLE.equalsIgnoreCase(item.path("trade_status").asText()),
                    minBase == null ? lot : minBase, lot, tick, decimalOrNull(item, "min_quote_amount"),
                    item.path("id").asText(base + "_" + quote), null);
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("Gate EU : paire illisible (" + base + "_" + quote + ")", e);
        }
    }

    private static BigDecimal decimalOrNull(JsonNode item, String field) {
        String text = item.path(field).asText("");
        return text.isBlank() ? null : new BigDecimal(text);
    }
}
