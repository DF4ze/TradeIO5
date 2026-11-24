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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Toutes les paires spot Kraken en un seul appel public : {@code GET /0/public/AssetPairs}. Noms d'actifs normalisés par
 * {@link KrakenAssetNames} (XXBT -> BTC, ZUSD -> USD...). Négociable si {@code status = online}. Paires « dark pool »
 * ({@code .d}) ignorées. {@code minSz = ordermin}, {@code lotSz = 10^-lot_decimals}, {@code tickSz = tick_size}
 * (sinon {@code 10^-pair_decimals}), {@code costMin = costmin}, {@code nativePair = altname} (nom accepté par {@code AddOrder}),
 * {@code feeCurrency = fee_volume_currency}. Volume 24 h en cotation = {@code v[1] × p[1]} (volume base × VWAP) lu par un second appel
 * public {@code /0/public/Ticker} (toutes les paires, un appel par rafraîchissement) ; son échec n'empêche pas le catalogue (volume inconnu, WARN).
 */
@Slf4j
@Component
public class KrakenInstrumentListClient implements InstrumentListClient {

    static final String ASSET_PAIRS_PATH = "/0/public/AssetPairs";
    /** Sans paramètre {@code pair}, {@code Ticker} renvoie TOUTES les paires en un appel : volume 24 h ({@code v[1]}) × VWAP 24 h ({@code p[1]}). */
    static final String TICKER_PATH = "/0/public/Ticker";

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
        List<Listed> listed = parse(body, fetchVolumes(provider));
        log.debug("Kraken : {} paires spot lues", listed.size());
        return listed;
    }

    private static Map<String, BigDecimal> fetchVolumes(WebProvider provider) {
        try {
            String body = WebClient.builder().baseUrl(provider.getApiBaseUrl()).codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024)).build()
                    .get()
                    .uri(TICKER_PATH)
                    .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                    .block(TIMEOUT);
            return parseVolumes(body);
        } catch (RuntimeException e) {
            log.warn("Kraken : volumes 24 h indisponibles ({}), volume inconnu", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /** Package-private pour les tests : {@code nom de paire (clé de la réponse) -> volume en cotation} ; réponse illisible => map vide. */
    static Map<String, BigDecimal> parseVolumes(String body) {
        Map<String, BigDecimal> volumes = new HashMap<>();
        try {
            JsonNode root = MAPPER.readTree(body == null ? "" : body);
            JsonNode errors = root.path("error");
            if (errors.isArray() && !errors.isEmpty()) {
                log.warn("Kraken : Ticker en erreur {}, volume inconnu", errors);
                return Map.of();
            }
            Iterator<Map.Entry<String, JsonNode>> entries = root.path("result").fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String volume = entry.getValue().path("v").path(1).asText("");
                String vwap = entry.getValue().path("p").path(1).asText("");
                if (!volume.isBlank() && !vwap.isBlank()) {
                    volumes.put(entry.getKey(), new BigDecimal(volume).multiply(new BigDecimal(vwap)));
                }
            }
        } catch (Exception e) {
            log.warn("Kraken : volumes 24 h illisibles ({})", e.getClass().getSimpleName());
            return Map.of();
        }
        return volumes;
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body) {
        return parse(body, Map.of());
    }

    /** Package-private pour les tests. */
    static List<Listed> parse(String body, Map<String, BigDecimal> volumes) {
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
            parseOne(entry.getKey(), entry.getValue(), volumes).ifPresent(l -> byId.putIfAbsent(l.instId(), l));
        }
        if (byId.isEmpty()) {
            throw new InstrumentLookupException("Kraken : aucune paire exploitable dans la réponse AssetPairs");
        }
        return new ArrayList<>(byId.values());
    }

    private static Optional<Listed> parseOne(String pairName, JsonNode node, Map<String, BigDecimal> volumes) {
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
            BigDecimal costMin = node.hasNonNull("costmin") ? new BigDecimal(node.path("costmin").asText()) : null;
            String nativePair = node.path("altname").asText("").isBlank() ? pairName : node.path("altname").asText();
            String rawFeeCurrency = node.path("fee_volume_currency").asText("");
            String feeCurrency = rawFeeCurrency.isBlank() ? null : KrakenAssetNames.toSymbol(rawFeeCurrency).orElse(rawFeeCurrency);
            return Optional.of(new Listed(base.get() + "-" + quote.get(), base.get(), quote.get(),
                    ONLINE_STATUS.equals(node.path("status").asText(ONLINE_STATUS)), min, lot, tick, costMin, nativePair,
                    feeCurrency, volumes.getOrDefault(pairName, volumes.get(nativePair))));
        } catch (NumberFormatException e) {
            log.warn("Kraken : paire ignorée (tailles illisibles) {}", pairName);
            return Optional.empty();
        }
    }
}
