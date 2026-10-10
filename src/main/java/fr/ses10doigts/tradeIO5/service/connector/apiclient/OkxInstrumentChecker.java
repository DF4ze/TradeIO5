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

/**
 * Paire spot OKX : {@code GET /api/v5/public/instruments?instType=SPOT&instId=BTC-USDC} (public, sans signature).
 * Instrument absent : {@code data} vide ou code {@code 51001} ; négociable si {@code state = live}.
 */
@Component
public class OkxInstrumentChecker implements SpotInstrumentChecker {

    static final String INSTRUMENTS_PATH = "/api/v5/public/instruments";

    private static final String OK_CODE = "0";
    private static final String UNKNOWN_INSTRUMENT_CODE = "51001";
    private static final String LIVE_STATE = "live";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.OKX;
    }

    @Override
    public boolean isTradable(WebProvider provider, String base, String quote) {
        if (provider == null || provider.getApiBaseUrl() == null || provider.getApiBaseUrl().isBlank()) {
            throw new InstrumentLookupException("OKX : URL de l'API absente");
        }
        String instId = base + "-" + quote;
        String body;
        try {
            body = WebClient.builder().baseUrl(provider.getApiBaseUrl()).build()
                    .get()
                    .uri(uri -> uri.path(INSTRUMENTS_PATH).queryParam("instType", "SPOT")
                            .queryParam("instId", instId).build())
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new InstrumentLookupException("OKX : appel instruments impossible (" + e.getClass().getSimpleName() + ")", e);
        }
        return parse(body);
    }

    /** Package-private pour les tests. */
    static boolean parse(String body) {
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
        if (UNKNOWN_INSTRUMENT_CODE.equals(code)) {
            return false;
        }
        if (!OK_CODE.equals(code)) {
            throw new InstrumentLookupException("OKX : erreur API instruments code=" + code);
        }
        JsonNode data = root.path("data");
        return data.isArray() && !data.isEmpty() && LIVE_STATE.equals(data.path(0).path("state").asText(""));
    }
}
