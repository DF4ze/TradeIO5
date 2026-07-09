package fr.ses10doigts.tradeIO5.service.tree.indicator.external.stablecoin;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.StablecoinMarketCapResponse;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client DefiLlama pour la capitalisation totale des stablecoins (étude "indicateurs-macro-externes",
 * §7) : {@code GET /stablecoins?includePrices=true}, gratuit, sans clé API. Même patron que
 * {@link fr.ses10doigts.tradeIO5.service.tree.indicator.external.feargreed.CoinstatsFearAndGreedClient} :
 * jamais d'exception qui remonte, toujours un {@link StablecoinMarketCapResponse#invalid()} propre
 * en cas de panne/timeout/erreur HTTP.
 */
@Component
public class DefiLlamaStablecoinClient extends AbstractExternalIndicator implements StablecoinMarketCapProvider {

    private static final String PEGGED_USD = "peggedUSD";

    private final Logger logger = LoggerFactory.getLogger(DefiLlamaStablecoinClient.class);

    @Override
    public StablecoinMarketCapResponse fetch(ApiCredentialDTO credential) {
        try {
            DefiLlamaStablecoinsRawResponse raw = getStablecoins(credential);
            return aggregate(raw);
        } catch (ExternalApiException e) {
            logger.warn("{} unavailable: {}", credential.provider(), e.getMessage());
            return StablecoinMarketCapResponse.invalid();
        } catch (Exception e) {
            logger.error("{} unexpected error", credential.provider(), e);
            return StablecoinMarketCapResponse.invalid();
        }
    }

    private DefiLlamaStablecoinsRawResponse getStablecoins(ApiCredentialDTO credential) {
        return getWebClient(credential).get()
                .uri("/stablecoins?includePrices=true")
                .retrieve()
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException(
                                        "DefiLlama 4xx: " + body))
                )
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException(
                                        "DefiLlama 5xx: " + body))
                )
                .bodyToMono(DefiLlamaStablecoinsRawResponse.class)
                // même marge que CoinstatsFearAndGreedClient (20s) : le payload est plus gros ici
                // (plusieurs centaines d'entrées), pas de raison de couper plus court.
                .timeout(Duration.ofSeconds(20))
                .block();
    }

    /**
     * Somme {@code circulating.peggedUSD} (et les 3 mêmes champs d'évolution) sur tous les
     * {@code peggedAssets} dont {@code pegType == "peggedUSD"} — les stablecoins peggés sur
     * d'autres devises (EUR, etc.) sont ignorés, comme demandé par l'étude §7. Isolée de l'appel
     * réseau pour être testable en unitaire (patron {@code BinanceMarketDataApiClient.mapKlinesResponse}).
     */
    static StablecoinMarketCapResponse aggregate(DefiLlamaStablecoinsRawResponse raw) {
        if (raw == null || raw.getPeggedAssets() == null) {
            return StablecoinMarketCapResponse.invalid();
        }

        List<DefiLlamaStablecoinsRawResponse.PeggedAsset> assets = raw.getPeggedAssets();

        double total = 0;
        double totalPrevDay = 0;
        double totalPrevWeek = 0;
        double totalPrevMonth = 0;

        for (DefiLlamaStablecoinsRawResponse.PeggedAsset asset : assets) {
            if (asset == null || !PEGGED_USD.equals(asset.getPegType())) {
                continue;
            }
            total += extract(asset.getCirculating());
            totalPrevDay += extract(asset.getCirculatingPrevDay());
            totalPrevWeek += extract(asset.getCirculatingPrevWeek());
            totalPrevMonth += extract(asset.getCirculatingPrevMonth());
        }

        return StablecoinMarketCapResponse.builder()
                .valid(true)
                .total(total)
                .totalPrevDay(totalPrevDay)
                .totalPrevWeek(totalPrevWeek)
                .totalPrevMonth(totalPrevMonth)
                .build();
    }

    private static double extract(Map<String, Double> circulating) {
        if (circulating == null) {
            return 0.0;
        }
        Double value = circulating.get(PEGGED_USD);
        return value != null ? value : 0.0;
    }
}
