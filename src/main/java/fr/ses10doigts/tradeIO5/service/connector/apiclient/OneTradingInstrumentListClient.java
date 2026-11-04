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
 * Toutes les paires spot One Trading en un appel public : {@code GET /instruments?type=SPOT}. Négociable si
 * {@code state = ACTIVE}. {@code lotSz = 10^-amount_precision}, {@code tickSz = 10^-market_precision}, {@code costMin =
 * min_size} (montant minimum en cotation : 10 sur BTC_USDC), {@code minSz = lotSz} (la doc ne donne pas de taille minimale en
 * base), {@code nativePair} = code One Trading ({@code BTC_USDC}). Futures ignorés. Le provider doit être déclaré et actif.
 */
@Slf4j
@Component
public class OneTradingInstrumentListClient implements InstrumentListClient {

    static final String INSTRUMENTS_PATH = "/instruments?type=SPOT";

    private static final String SPOT = "SPOT";
    private static final String ACTIVE = "ACTIVE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.ONE_TRADING;
    }

    @Override
    public List<Listed> fetchAll(WebProvider provider) {
        if (!OneTradingRequests.usable(provider)) {
            throw new InstrumentLookupException("One Trading : provider non déclaré ou désactivé");
        }
        String body;
        try {
            OneTradingRequests.Reply reply = OneTradingRequests.publicGet(provider, INSTRUMENTS_PATH);
            if (!reply.ok()) {
                throw new InstrumentLookupException("One Trading : instruments en erreur HTTP " + reply.status());
            }
            body = reply.body();
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
        List<Listed> listed = parse(body);
        log.debug("One Trading : {} paires spot lues", listed.size());
        return listed;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("One Trading : réponse instruments illisible", e);
        }
        JsonNode array = root != null && root.isObject() ? root.path("instruments") : root;
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new InstrumentLookupException("One Trading : liste de paires vide ou inattendue");
        }
        List<Listed> out = new ArrayList<>();
        for (JsonNode item : array) {
            if (!SPOT.equalsIgnoreCase(item.path("type").asText(SPOT))) {
                continue;
            }
            Listed listed = toListed(item);
            if (listed != null) {
                out.add(listed);
            }
        }
        if (out.isEmpty()) {
            throw new InstrumentLookupException("One Trading : aucune paire spot exploitable");
        }
        return out;
    }

    private static Listed toListed(JsonNode item) {
        String base = item.path("base").path("code").asText("").toUpperCase();
        String quote = item.path("quote").path("code").asText("").toUpperCase();
        if (base.isBlank() || quote.isBlank() || !item.path("amount_precision").canConvertToInt()
                || !item.path("market_precision").canConvertToInt()) {
            return null;
        }
        try {
            BigDecimal lot = BigDecimal.ONE.movePointLeft(item.path("amount_precision").asInt());
            BigDecimal tick = BigDecimal.ONE.movePointLeft(item.path("market_precision").asInt());
            String minSize = item.path("min_size").asText("");
            BigDecimal costMin = minSize.isBlank() ? null : new BigDecimal(minSize);
            return new Listed(base + "-" + quote, base, quote, ACTIVE.equalsIgnoreCase(item.path("state").asText()), lot, lot, tick,
                    costMin, item.path("id").asText(base + "_" + quote), null);
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("One Trading : paire illisible (" + base + "_" + quote + ")", e);
        }
    }
}
