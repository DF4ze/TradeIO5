package fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Résout le code de marché Coinalyze (ex. {@code "BTCUSDT_PERP.A"}) correspondant à un symbole
 * TradeIO5 (ex. {@code "BTCUSDT"}) — étape 0 de l'étude "indicateurs-macro-externes" (§6b/§9/§12) :
 * le suffixe après le point dans le symbole Coinalyze encode l'exchange et ne doit **pas** être
 * deviné/codé en dur sans vérification manuelle contre l'API.
 * <p>
 * <b>Choix d'implémentation fait ici</b> (documenté comme demandé par le prompt d'implémentation) :
 * résolution <i>dynamique</i> via {@code GET /v1/exchanges} + {@code GET /v1/future-markets},
 * plutôt qu'une table statique codée en dur — cette dernière aurait nécessité de vérifier
 * manuellement chaque correspondance symbole/suffixe contre l'API Coinalyze au moment de
 * l'implémentation, vérification qui n'a pas pu être faite ici (pas d'accès réseau sortant vers
 * coinalyze.net dans cet environnement). La résolution dynamique reste correcte par construction
 * (elle lit la correspondance exacte renvoyée par l'API elle-même) au prix d'un aller-retour réseau
 * supplémentaire la première fois qu'un symbole est demandé — atténué par un cache mémoire
 * (le mapping symbole -&gt; code Coinalyze ne change pas à l'échelle d'un run applicatif).
 */
@Component
public class CoinalyzeSymbolResolver extends AbstractExternalIndicator {

    // Coinalyze agrège ~25 exchanges ; TradeIO5 ne trade aujourd'hui que sur Binance/Kraken
    // (cf. WebProviderInitializer) — Binance est le candidat naturel pour la résolution des
    // marchés futures/perpétuels (Kraken Spot n'a pas d'équivalent futures pertinent ici).
    private static final String DEFAULT_EXCHANGE_HINT = "Binance";

    private final Logger logger = LoggerFactory.getLogger(CoinalyzeSymbolResolver.class);

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * @return le code Coinalyze exact pour {@code tradeIO5Symbol} sur l'exchange par défaut
     * (Binance), ou {@code null} si la résolution échoue (réseau, symbole introuvable) — jamais
     * d'exception : l'appelant doit traiter {@code null} comme "indicateur invalid".
     */
    public String resolve(ApiCredentialDTO credential, String tradeIO5Symbol) {
        if (tradeIO5Symbol == null) {
            return null;
        }

        String cacheKey = DEFAULT_EXCHANGE_HINT + ":" + tradeIO5Symbol.toUpperCase();
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            List<CoinalyzeExchange> exchanges = fetchExchanges(credential);
            List<CoinalyzeFutureMarket> markets = fetchFutureMarkets(credential);

            String resolved = findCoinalyzeSymbol(exchanges, markets, DEFAULT_EXCHANGE_HINT, tradeIO5Symbol);
            if (resolved != null) {
                cache.put(cacheKey, resolved);
            } else {
                logger.warn("Coinalyze : aucun marché future trouvé pour {} sur {}", tradeIO5Symbol, DEFAULT_EXCHANGE_HINT);
            }
            return resolved;
        } catch (ExternalApiException e) {
            logger.warn("Coinalyze symbol resolution unavailable for {} : {}", tradeIO5Symbol, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Coinalyze symbol resolution unexpected error for {}", tradeIO5Symbol, e);
            return null;
        }
    }

    private List<CoinalyzeExchange> fetchExchanges(ApiCredentialDTO credential) {
        return getWebClient(credential).get()
                .uri(uriBuilder -> uriBuilder.path("/exchanges")
                        .queryParam("api_key", credential.apiKey())
                        .build())
                .retrieve()
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException("Coinalyze 4xx (exchanges): " + body))
                )
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException("Coinalyze 5xx (exchanges): " + body))
                )
                .bodyToMono(new ParameterizedTypeReference<List<CoinalyzeExchange>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    private List<CoinalyzeFutureMarket> fetchFutureMarkets(ApiCredentialDTO credential) {
        return getWebClient(credential).get()
                .uri(uriBuilder -> uriBuilder.path("/future-markets")
                        .queryParam("api_key", credential.apiKey())
                        .build())
                .retrieve()
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException("Coinalyze 4xx (future-markets): " + body))
                )
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException("Coinalyze 5xx (future-markets): " + body))
                )
                .bodyToMono(new ParameterizedTypeReference<List<CoinalyzeFutureMarket>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    /**
     * Logique pure de filtrage, isolée de l'appel réseau pour être testable en unitaire (patron
     * {@code BinanceMarketDataApiClient.mapKlinesResponse}) : trouve d'abord le {@code code}
     * exchange dont le {@code name} contient {@code exchangeNameHint} (ex. "Binance"), puis le
     * marché future dont {@code exchange} correspond à ce code et {@code symbolOnExchange}
     * correspond au symbole TradeIO5 demandé.
     */
    static String findCoinalyzeSymbol(
            List<CoinalyzeExchange> exchanges,
            List<CoinalyzeFutureMarket> markets,
            String exchangeNameHint,
            String tradeIO5Symbol) {
        if (exchanges == null || markets == null || tradeIO5Symbol == null) {
            return null;
        }

        String exchangeCode = exchanges.stream()
                .filter(e -> e.getName() != null && e.getName().toLowerCase().contains(exchangeNameHint.toLowerCase()))
                .map(CoinalyzeExchange::getCode)
                .findFirst()
                .orElse(null);

        if (exchangeCode == null) {
            return null;
        }

        return markets.stream()
                .filter(m -> exchangeCode.equals(m.getExchange()))
                .filter(m -> tradeIO5Symbol.equalsIgnoreCase(m.getSymbolOnExchange()))
                .map(CoinalyzeFutureMarket::getSymbol)
                .findFirst()
                .orElse(null);
    }
}
