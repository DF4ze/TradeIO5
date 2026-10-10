package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Toutes les paires spot OKX en <b>un seul</b> appel public : {@code GET /api/v5/public/instruments?instType=SPOT}.
 * Négociable si {@code state = live}. Parsing défensif : une entrée illisible est ignorée (WARN) ; une réponse sans
 * aucune paire exploitable est une erreur (le catalogue en base n'est jamais vidé par une réponse vide).
 */
@Slf4j
@Component
public class OkxInstrumentListClient implements InstrumentListClient {

    static final String INSTRUMENTS_PATH = "/api/v5/public/instruments";

    private static final String LIVE_STATE = "live";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public List<Listed> fetchAll(WebProvider provider) {
        if (provider == null || provider.getApiBaseUrl() == null || provider.getApiBaseUrl().isBlank()) {
            throw new InstrumentLookupException("OKX : URL de l'API absente");
        }
        String body;
        try {
            body = WebClient.builder().baseUrl(provider.getApiBaseUrl()).codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build()
                    .get()
                    .uri(uri -> uri.path(INSTRUMENTS_PATH).queryParam("instType", "SPOT").build())
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new InstrumentLookupException("OKX : appel instruments impossible (" + e.getClass().getSimpleName() + ")", e);
        }
        List<Listed> listed = parse(body);
        log.debug("OKX : {} paires spot lues", listed.size());
        return listed;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new InstrumentLookupException("OKX : réponse instruments illisible", e);
        }
        if (root == null || !root.isObject()) {
            throw new InstrumentLookupException("OKX : réponse instruments inattendue");
        }
        String code = root.path("code").asText("");
        if (!OkxSignedRequests.OK_CODE.equals(code)) {
            throw new InstrumentLookupException("OKX : erreur API instruments code=" + code);
        }
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new InstrumentLookupException("OKX : liste d'instruments vide");
        }
        List<Listed> result = new ArrayList<>(data.size());
        for (JsonNode node : data) {
            Listed item = parseOne(node);
            if (item != null) {
                result.add(item);
            }
        }
        if (result.isEmpty()) {
            throw new InstrumentLookupException("OKX : aucune paire exploitable dans la réponse instruments");
        }
        return result;
    }

    private static Listed parseOne(JsonNode node) {
        String base = node.path("baseCcy").asText("").toUpperCase();
        String quote = node.path("quoteCcy").asText("").toUpperCase();
        if (base.isBlank() || quote.isBlank()) {
            log.warn("OKX : instrument ignoré (devises absentes) instId={}", node.path("instId").asText(""));
            return null;
        }
        try {
            return new Listed(base + "-" + quote, base, quote, LIVE_STATE.equals(node.path("state").asText("")),
                    new BigDecimal(node.path("minSz").asText("")), new BigDecimal(node.path("lotSz").asText("")),
                    new BigDecimal(node.path("tickSz").asText("")));
        } catch (NumberFormatException e) {
            log.warn("OKX : instrument ignoré (tailles illisibles) instId={}", node.path("instId").asText(""));
            return null;
        }
    }
}
