package fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client Yahoo Finance pour SP500/NASDAQ (endpoint {@code /v8/finance/chart/{symbol}}, public,
 * sans clé API) — retenu le 2026-07-15 après vérification en réel que les tickers Twelve Data
 * {@code SPX}/{@code IXIC} sont verrouillés au palier payant (404 explicite "available starting
 * with the Grow or Venture plan" pour {@code SPX}/{@code NDX} ; {@code IXIC} en plus invalide en
 * tant que symbole Twelve Data). {@code DXY} n'est pas concerné, reste sur
 * {@code TwelveDataQuoteClient} (déjà fonctionnel).
 * <p>
 * <b>Header {@code User-Agent} obligatoire</b> : confirmé par test réel — sans lui, Yahoo répond
 * {@code 429} systématiquement (probablement un filtrage anti-bot sur le User-Agent par défaut de
 * reactor-netty), avec un {@code User-Agent} de navigateur classique la même requête répond
 * {@code 200}. D'où le header explicite sur chaque appel ci-dessous plutôt qu'une config globale
 * sur {@link AbstractExternalIndicator#getWebClient}, pour ne pas affecter les autres clients.
 * <p>
 * Format de réponse vérifié contre un appel réel le 2026-07-15 (contrairement à
 * {@code TwelveDataQuoteClient} au moment de son écriture) :
 * <pre>
 * {"chart":{"result":[{"meta":{"regularMarketPrice":7543.59,"regularMarketTime":1784062596,...}}],"error":null}}
 * </pre>
 * et en cas de symbole invalide/inconnu :
 * <pre>
 * {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}
 * </pre>
 * Parsing défensif via {@link JsonNode} (jamais d'exception, {@code null}/entrée absente plutôt
 * qu'une valeur par défaut) — même garde-fou que {@code TwelveDataQuoteClient}.
 */
@Component
public class YahooFinanceQuoteClient extends AbstractExternalIndicator implements YahooFinanceQuoteProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // cf. javadoc de classe : header obligatoire, sinon 429 systématique côté Yahoo.
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final Logger logger = LoggerFactory.getLogger(YahooFinanceQuoteClient.class);

    @Override
    public Map<String, YahooFinanceQuote> fetchQuotes(ApiCredentialDTO credential, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }

        Map<String, YahooFinanceQuote> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            YahooFinanceQuote quote = fetchOne(credential, symbol);
            if (quote != null) {
                result.put(symbol, quote);
            }
        }
        return result;
    }

    private YahooFinanceQuote fetchOne(ApiCredentialDTO credential, String symbol) {
        try {
            String body = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/v8/finance/chart/{symbol}")
                            .queryParam("interval", "1d")
                            .queryParam("range", "1d")
                            .build(symbol))
                    .header(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .block();

            return parseQuote(body);
        } catch (ExternalApiException e) {
            logger.warn("Yahoo Finance unavailable for {}: {}", symbol, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Yahoo Finance unexpected error for {}", symbol, e);
            return null;
        }
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire (patron
     * {@code TwelveDataQuoteClient.parseQuotes}). Retourne {@code null} sur corps invalide,
     * {@code result} absent/vide/null (symbole inconnu, cf. javadoc de classe), ou
     * {@code regularMarketPrice} manquant — jamais d'exception.
     */
    static YahooFinanceQuote parseQuote(String body) {
        JsonNode root = readTree(body);
        if (root == null) {
            return null;
        }

        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return null;
        }

        JsonNode meta = result.get(0).path("meta");
        if (meta.isMissingNode() || !meta.has("regularMarketPrice") || meta.get("regularMarketPrice").isNull()) {
            return null;
        }

        double price = meta.get("regularMarketPrice").asDouble();
        Long timestamp = meta.has("regularMarketTime") && !meta.get("regularMarketTime").isNull()
                ? meta.get("regularMarketTime").asLong()
                : null;
        Double previousClose = readPreviousClose(meta);

        return new YahooFinanceQuote(price, timestamp, previousClose);
    }

    /**
     * {@code previousClose}, avec repli sur {@code chartPreviousClose} (cf. javadoc
     * {@link YahooFinanceQuote}) — jamais vérifié pour tous les symboles contre un appel réel
     * (seul {@code regularMarketPrice}/{@code regularMarketTime} l'ont été le 2026-07-15), d'où le
     * repli défensif plutôt qu'un seul nom de champ supposé fiable.
     */
    private static Double readPreviousClose(JsonNode meta) {
        if (meta.has("previousClose") && !meta.get("previousClose").isNull()) {
            return meta.get("previousClose").asDouble();
        }
        if (meta.has("chartPreviousClose") && !meta.get("chartPreviousClose").isNull()) {
            return meta.get("chartPreviousClose").asDouble();
        }
        return null;
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

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Yahoo Finance 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Yahoo Finance 5xx: " + body));
    }
}
