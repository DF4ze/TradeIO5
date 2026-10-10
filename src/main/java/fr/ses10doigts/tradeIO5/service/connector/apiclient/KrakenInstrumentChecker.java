package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.SpotInstrumentChecker;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Iterator;

/**
 * Paire spot Kraken : {@code GET /0/public/AssetPairs?pair=<BASE><QUOTE>} (public). Kraken nomme BTC « XBT » dans les
 * noms de paires. Paire inconnue : erreur {@code Unknown asset pair} ou {@code result} vide ; négociable si
 * {@code status = online}.
 */
@Component
public class KrakenInstrumentChecker implements SpotInstrumentChecker {

    static final String ASSET_PAIRS_PATH = "/0/public/AssetPairs";

    private static final String UNKNOWN_PAIR = "Unknown asset pair";
    private static final String ONLINE_STATUS = "online";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.KRAKEN;
    }

    @Override
    public boolean isTradable(WebProvider provider, String base, String quote) {
        if (provider == null || provider.getApiBaseUrl() == null || provider.getApiBaseUrl().isBlank()) {
            throw new InstrumentLookupException("Kraken : URL de l'API absente");
        }
        String pair = krakenName(base) + krakenName(quote);
        String body;
        try {
            body = WebClient.builder().baseUrl(provider.getApiBaseUrl()).build()
                    .get()
                    .uri(uri -> uri.path(ASSET_PAIRS_PATH).queryParam("pair", pair).build())
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new InstrumentLookupException("Kraken : appel AssetPairs impossible (" + e.getClass().getSimpleName() + ")", e);
        }
        return parse(body);
    }

    private static String krakenName(String symbol) {
        return "BTC".equals(symbol) ? "XBT" : symbol;
    }

    /** Package-private pour les tests. */
    static boolean parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("Kraken : réponse AssetPairs illisible", e);
        }
        if (root == null || !root.isObject()) {
            throw new InstrumentLookupException("Kraken : réponse AssetPairs inattendue");
        }
        JsonNode errors = root.path("error");
        if (errors.isArray() && !errors.isEmpty()) {
            if (errors.toString().contains(UNKNOWN_PAIR)) {
                return false;
            }
            throw new InstrumentLookupException("Kraken : erreur API AssetPairs " + errors);
        }
        JsonNode result = root.path("result");
        if (!result.isObject() || result.isEmpty()) {
            return false;
        }
        Iterator<JsonNode> pairs = result.elements();
        while (pairs.hasNext()) {
            if (ONLINE_STATUS.equals(pairs.next().path("status").asText(ONLINE_STATUS))) {
                return true;
            }
        }
        return false;
    }
}
