package fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client Twelve Data (étude "indicateurs-macro-externes" §14, items E/F) : un seul provider pour
 * la formule DXY (6 paires forex, {@code /price}) et les indices SP500/NASDAQ ({@code /quote}),
 * auth par {@code apikey} en query param.
 * <p>
 * <b>Format de réponse non vérifié contre un appel réel</b> (pas de clé Twelve Data disponible au
 * moment de cette implémentation — cf. prompt d'implémentation Lot 2, item E) : d'après le
 * comportement documenté du client officiel, une requête multi-symboles renvoie un objet JSON keyé
 * par symbole (chaque valeur de la forme {@code {"price": "1.1740"}} pour {@code /price}), tandis
 * qu'une requête à un seul symbole renvoie directement l'objet à plat (sans clé englobante). Le
 * parsing ci-dessous (voir {@link #parsePrices}/{@link #parseQuotes}) est écrit défensivement via
 * {@link JsonNode} (plutôt qu'un DTO Jackson strict) précisément pour tolérer un écart sur ce point
 * tant que ce n'est pas confirmé : une entrée dont la forme ne correspond pas est simplement
 * ignorée (jamais d'exception), et {@code parsePrices}/{@code parseQuotes} sont statiques et
 * testables indépendamment de l'appel réseau — <b>à revalider dès la création d'une vraie clé</b>.
 * <p>
 * Mêmes garde-fous que {@code CoinalyzeClient} : jamais d'exception qui remonte, toujours une map
 * vide en cas de panne/timeout/erreur HTTP.
 */
@Component
public class TwelveDataQuoteClient extends AbstractExternalIndicator implements TwelveDataQuoteProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger logger = LoggerFactory.getLogger(TwelveDataQuoteClient.class);

    @Override
    public Map<String, Double> fetchPrices(ApiCredentialDTO credential, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }

        try {
            String body = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/price")
                            .queryParam("symbol", String.join(",", symbols))
                            .queryParam("apikey", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            return parsePrices(body, symbols);
        } catch (ExternalApiException e) {
            logger.warn("Twelve Data (price) unavailable for {}: {}", symbols, e.getMessage());
            return Map.of();
        } catch (Exception e) {
            logger.error("Twelve Data (price) unexpected error for {}", symbols, e);
            return Map.of();
        }
    }

    @Override
    public Map<String, TwelveDataQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }

        try {
            String body = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/quote")
                            .queryParam("symbol", String.join(",", symbols))
                            .queryParam("apikey", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            return parseQuotes(body, symbols);
        } catch (ExternalApiException e) {
            logger.warn("Twelve Data (quote) unavailable for {}: {}", symbols, e.getMessage());
            return Map.of();
        } catch (Exception e) {
            logger.error("Twelve Data (quote) unexpected error for {}", symbols, e);
            return Map.of();
        }
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire (patron
     * {@code DefiLlamaStablecoinClient.aggregate}). Gère les deux formes possibles (objet keyé par
     * symbole pour une requête multi-symboles, objet à plat {@code {"price":...}} pour un seul
     * symbole) — voir avertissement en tête de classe.
     */
    static Map<String, Double> parsePrices(String body, List<String> requestedSymbols) {
        JsonNode root = readTree(body);
        if (root == null) {
            return Map.of();
        }

        Map<String, Double> result = new LinkedHashMap<>();

        if (requestedSymbols.size() == 1 && root.has("price")) {
            Double price = parseDouble(root.path("price").asText(null));
            if (price != null) {
                result.put(requestedSymbols.getFirst(), price);
            }
            return result;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode priceNode = field.getValue().get("price");
            if (priceNode == null) {
                // Entrée en erreur côté Twelve Data (ex: {"code":400,"message":"..."}) pour ce
                // symbole précis : ignorée plutôt que de faire échouer tout le lot.
                continue;
            }
            Double price = parseDouble(priceNode.asText(null));
            if (price != null) {
                result.put(field.getKey(), price);
            }
        }
        return result;
    }

    /**
     * Même principe que {@link #parsePrices}, pour {@code /quote}. Noms de champs
     * {@code timestamp}/{@code is_market_open} non vérifiés (voir avertissement en tête de
     * classe) — absents proprement tolérés (map avec {@code timestampEpochSeconds}/
     * {@code marketOpen} à {@code null} plutôt qu'une entrée rejetée, tant que {@code close} est
     * présent).
     */
    static Map<String, TwelveDataQuote> parseQuotes(String body, List<String> requestedSymbols) {
        JsonNode root = readTree(body);
        if (root == null) {
            return Map.of();
        }

        Map<String, TwelveDataQuote> result = new LinkedHashMap<>();

        if (requestedSymbols.size() == 1 && (root.has("close") || root.has("price"))) {
            TwelveDataQuote quote = toQuote(root);
            if (quote != null) {
                result.put(requestedSymbols.getFirst(), quote);
            }
            return result;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            TwelveDataQuote quote = toQuote(field.getValue());
            if (quote != null) {
                result.put(field.getKey(), quote);
            }
        }
        return result;
    }

    private static TwelveDataQuote toQuote(JsonNode node) {
        JsonNode priceNode = node.has("close") ? node.get("close") : node.get("price");
        if (priceNode == null) {
            return null;
        }
        Double price = parseDouble(priceNode.asText(null));
        if (price == null) {
            return null;
        }

        Long timestamp = node.has("timestamp") && !node.get("timestamp").isNull()
                ? node.get("timestamp").asLong()
                : null;
        Boolean marketOpen = node.has("is_market_open") && !node.get("is_market_open").isNull()
                ? node.get("is_market_open").asBoolean()
                : null;

        return new TwelveDataQuote(price, timestamp, marketOpen);
    }

    private static JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Twelve Data 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Twelve Data 5xx: " + body));
    }
}
