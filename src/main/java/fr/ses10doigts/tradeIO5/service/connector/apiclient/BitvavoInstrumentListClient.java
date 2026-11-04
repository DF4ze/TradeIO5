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
 * Toutes les paires Bitvavo en un appel public : {@code GET /markets}. Négociable si {@code status = trading}.
 * {@code lotSz = 10^-quantityDecimals}, {@code tickSz = tickSize}, {@code minSz = minOrderInBaseAsset}, {@code costMin =
 * minOrderInQuoteAsset}, {@code nativePair} = identifiant Bitvavo ({@code BTC-EUR}, même forme que l'identifiant interne).
 * Le provider doit être déclaré et actif.
 */
@Slf4j
@Component
public class BitvavoInstrumentListClient implements InstrumentListClient {

    static final String MARKETS_PATH = "/markets";

    private static final String TRADING = "trading";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.BITVAVO;
    }

    @Override
    public List<Listed> fetchAll(WebProvider provider) {
        if (!BitvavoRequests.usable(provider)) {
            throw new InstrumentLookupException("Bitvavo : provider non déclaré ou désactivé");
        }
        String body;
        try {
            BitvavoRequests.Reply reply = BitvavoRequests.publicGet(provider, MARKETS_PATH);
            if (!reply.ok()) {
                throw new InstrumentLookupException("Bitvavo : marchés en erreur HTTP " + reply.status());
            }
            body = reply.body();
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
        List<Listed> listed = parse(body);
        log.debug("Bitvavo : {} paires lues", listed.size());
        return listed;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("Bitvavo : réponse marchés illisible", e);
        }
        if (root == null || !root.isArray() || root.isEmpty()) {
            throw new InstrumentLookupException("Bitvavo : liste de paires vide ou inattendue");
        }
        List<Listed> out = new ArrayList<>();
        for (JsonNode item : root) {
            Listed listed = toListed(item);
            if (listed != null) {
                out.add(listed);
            }
        }
        if (out.isEmpty()) {
            throw new InstrumentLookupException("Bitvavo : aucune paire exploitable");
        }
        return out;
    }

    private static Listed toListed(JsonNode item) {
        String market = item.path("market").asText("").toUpperCase();
        String base = item.path("base").asText("").toUpperCase();
        String quote = item.path("quote").asText("").toUpperCase();
        if (market.isBlank() || base.isBlank() || quote.isBlank() || !item.path("quantityDecimals").canConvertToInt()
                || item.path("tickSize").asText("").isBlank()) {
            return null;
        }
        try {
            BigDecimal lot = BigDecimal.ONE.movePointLeft(item.path("quantityDecimals").asInt());
            BigDecimal tick = new BigDecimal(item.path("tickSize").asText());
            BigDecimal minBase = decimalOrNull(item, "minOrderInBaseAsset");
            return new Listed(base + "-" + quote, base, quote, TRADING.equalsIgnoreCase(item.path("status").asText()),
                    minBase == null ? lot : minBase, lot, tick, decimalOrNull(item, "minOrderInQuoteAsset"), market, null);
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("Bitvavo : paire illisible (" + market + ")", e);
        }
    }

    private static BigDecimal decimalOrNull(JsonNode item, String field) {
        String text = item.path(field).asText("");
        return text.isBlank() ? null : new BigDecimal(text);
    }
}
