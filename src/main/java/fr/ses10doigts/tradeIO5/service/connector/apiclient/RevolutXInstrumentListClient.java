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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Toutes les paires Revolut X en un appel public : {@code GET /1.0/public/configuration/pairs?region=EEA} (objet dont les clés
 * sont les paires). Négociable si {@code status = active}. {@code minSz = min_order_size} (base), {@code lotSz = base_step},
 * {@code tickSz = quote_step} (hypothèse : la doc ne dit pas explicitement que c'est le pas de prix), {@code costMin =
 * min_order_size_quote}, {@code nativePair} = {@code BASE-QUOTE} (symbole des routes). Le provider doit être déclaré et actif.
 */
@Slf4j
@Component
public class RevolutXInstrumentListClient implements InstrumentListClient {

    static final String PAIRS_PATH = "/1.0/public/configuration/pairs";
    /** Région du compte (France = EEA) ; sans ce filtre la doc renvoie les deux régions. */
    static final String REGION_QUERY = "region=EEA";

    private static final String ACTIVE = "active";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.REVOLUT_X;
    }

    @Override
    public List<Listed> fetchAll(WebProvider provider) {
        if (!RevolutXRequests.usable(provider)) {
            throw new InstrumentLookupException("Revolut X : provider non déclaré ou désactivé");
        }
        String body;
        try {
            RevolutXRequests.Reply reply = RevolutXRequests.publicGet(provider, PAIRS_PATH, REGION_QUERY);
            if (!reply.ok()) {
                throw new InstrumentLookupException("Revolut X : paires en erreur HTTP " + reply.status());
            }
            body = reply.body();
        } catch (BalanceUnavailableException e) {
            throw new InstrumentLookupException(e.getMessage(), e);
        }
        List<Listed> listed = parse(body);
        log.debug("Revolut X : {} paires lues", listed.size());
        return listed;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("Revolut X : réponse paires illisible", e);
        }
        JsonNode pairs = root != null && root.path("data").isObject() ? root.path("data") : root;
        if (pairs == null || !pairs.isObject() || pairs.isEmpty()) {
            throw new InstrumentLookupException("Revolut X : liste de paires vide ou inattendue");
        }
        List<Listed> out = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = pairs.fields();
        while (fields.hasNext()) {
            Listed listed = toListed(fields.next().getValue());
            if (listed != null) {
                out.add(listed);
            }
        }
        if (out.isEmpty()) {
            throw new InstrumentLookupException("Revolut X : aucune paire exploitable");
        }
        return out;
    }

    private static Listed toListed(JsonNode item) {
        String base = item.path("base").asText("").toUpperCase();
        String quote = item.path("quote").asText("").toUpperCase();
        if (base.isBlank() || quote.isBlank()) {
            return null;
        }
        try {
            BigDecimal minSz = new BigDecimal(item.path("min_order_size").asText());
            BigDecimal lot = new BigDecimal(item.path("base_step").asText());
            BigDecimal tick = new BigDecimal(item.path("quote_step").asText());
            String minQuote = item.path("min_order_size_quote").asText("");
            BigDecimal costMin = minQuote.isBlank() ? null : new BigDecimal(minQuote);
            return new Listed(base + "-" + quote, base, quote, ACTIVE.equalsIgnoreCase(item.path("status").asText()), minSz, lot, tick,
                    costMin, base + "-" + quote, null);
        } catch (NumberFormatException e) {
            throw new InstrumentLookupException("Revolut X : paire illisible (" + base + "-" + quote + ")", e);
        }
    }
}
