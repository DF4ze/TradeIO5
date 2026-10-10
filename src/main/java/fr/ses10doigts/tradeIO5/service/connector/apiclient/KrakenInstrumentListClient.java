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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Toutes les paires spot Kraken en un seul appel public : {@code GET /0/public/AssetPairs}. Noms d'actifs normalisés par
 * {@link KrakenAssetNames} (XXBT -> BTC, ZUSD -> USD...). Négociable si {@code status = online}. Paires « dark pool »
 * ({@code .d}) ignorées. {@code minSz = ordermin}, {@code lotSz = 10^-lot_decimals}, {@code tickSz = tick_size}
 * (sinon {@code 10^-pair_decimals}).
 */
@Slf4j
@Component
public class KrakenInstrumentListClient implements InstrumentListClient {

    static final String ASSET_PAIRS_PATH = "/0/public/AssetPairs";

    private static final String ONLINE_STATUS = "online";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public WebProviderCode getProviderCode() {
        return WebProviderCode.KRAKEN;
    }

    @Override
    public List<Listed> fetchAll(WebProvider provider) {
        if (provider == null || provider.getApiBaseUrl() == null || provider.getApiBaseUrl().isBlank()) {
            throw new InstrumentLookupException("Kraken : URL de l'API absente");
        }
        String body;
        try {
            body = WebClient.builder().baseUrl(provider.getApiBaseUrl()).codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build()
                    .get()
                    .uri(ASSET_PAIRS_PATH)
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
        } catch (RuntimeException e) {
            throw new InstrumentLookupException("Kraken : appel AssetPairs impossible (" + e.getClass().getSimpleName() + ")", e);
        }
        List<Listed> listed = parse(body);
        log.debug("Kraken : {} paires spot lues", listed.size());
        return listed;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
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
            throw new InstrumentLookupException("Kraken : erreur API AssetPairs " + errors);
        }
        JsonNode result = root.path("result");
        if (!result.isObject() || result.isEmpty()) {
            throw new InstrumentLookupException("Kraken : liste de paires vide");
        }
        Map<String, Listed> byId = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = result.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            parseOne(entry.getKey(), entry.getValue()).ifPresent(l -> byId.putIfAbsent(l.instId(), l));
        }
        if (byId.isEmpty()) {
            throw new InstrumentLookupException("Kraken : aucune paire exploitable dans la réponse AssetPairs");
        }
        return new ArrayList<>(byId.values());
    }

    private static Optional<Listed> parseOne(String pairName, JsonNode node) {
        if (pairName.endsWith(".d")) {
            return Optional.empty();
        }
        Optional<String> base = KrakenAssetNames.toSymbol(node.path("base").asText(""));
        Optional<String> quote = KrakenAssetNames.toSymbol(node.path("quote").asText(""));
        if (base.isEmpty() || quote.isEmpty() || node.path("base").asText("").isBlank()
                || node.path("quote").asText("").isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal lot = BigDecimal.ONE.movePointLeft(node.path("lot_decimals").asInt(8));
            BigDecimal min = node.hasNonNull("ordermin") ? new BigDecimal(node.path("ordermin").asText()) : lot;
            BigDecimal tick = node.hasNonNull("tick_size") ? new BigDecimal(node.path("tick_size").asText())
                    : BigDecimal.ONE.movePointLeft(node.path("pair_decimals").asInt(8));
            return Optional.of(new Listed(base.get() + "-" + quote.get(), base.get(), quote.get(),
                    ONLINE_STATUS.equals(node.path("status").asText(ONLINE_STATUS)), min, lot, tick));
        } catch (NumberFormatException e) {
            log.warn("Kraken : paire ignorée (tailles illisibles) {}", pairName);
            return Optional.empty();
        }
    }
}
