package fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.FundingRateResponse;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.LiquidationHistoryResponse;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.OpenInterestHistoryResponse;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.OpenInterestResponse;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Client Coinalyze (étude "indicateurs-macro-externes", §6b/§9/§12) : un seul provider pour trois
 * besoins (Open Interest, Funding Rate, Liquidations), auth par {@code api_key} en query param,
 * rate limit 40 appels/minute par clé (non géré ici — aucun rate limiter côté client pour l'instant,
 * à ajouter si la limite s'avère atteinte en pratique). Mêmes garde-fous que
 * {@code CoinstatsFearAndGreedClient}/{@code DefiLlamaStablecoinClient} : jamais d'exception qui
 * remonte, toujours un DTO {@code invalid()} propre en cas de panne/timeout/erreur HTTP.
 */
@Component
public class CoinalyzeClient extends AbstractExternalIndicator {

    private final Logger logger = LoggerFactory.getLogger(CoinalyzeClient.class);

    public OpenInterestResponse fetchOpenInterest(ApiCredentialDTO credential, String coinalyzeSymbol) {
        try {
            List<OpenInterestResponse.Entry> entries = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/open-interest")
                            .queryParam("symbols", coinalyzeSymbol)
                            .queryParam("api_key", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(new ParameterizedTypeReference<List<OpenInterestResponse.Entry>>() {})
                    .timeout(Duration.ofSeconds(20))
                    .block();

            OpenInterestResponse response = new OpenInterestResponse();
            response.setEntries(entries);
            response.setValid(true);
            return response;
        } catch (ExternalApiException e) {
            logger.warn("Coinalyze (open-interest) unavailable for {}: {}", coinalyzeSymbol, e.getMessage());
            return OpenInterestResponse.invalid();
        } catch (Exception e) {
            logger.error("Coinalyze (open-interest) unexpected error for {}", coinalyzeSymbol, e);
            return OpenInterestResponse.invalid();
        }
    }

    public FundingRateResponse fetchFundingRate(ApiCredentialDTO credential, String coinalyzeSymbol) {
        try {
            List<FundingRateResponse.Entry> entries = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/funding-rate")
                            .queryParam("symbols", coinalyzeSymbol)
                            .queryParam("api_key", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(new ParameterizedTypeReference<List<FundingRateResponse.Entry>>() {})
                    .timeout(Duration.ofSeconds(20))
                    .block();

            FundingRateResponse response = new FundingRateResponse();
            response.setEntries(entries);
            response.setValid(true);
            return response;
        } catch (ExternalApiException e) {
            logger.warn("Coinalyze (funding-rate) unavailable for {}: {}", coinalyzeSymbol, e.getMessage());
            return FundingRateResponse.invalid();
        } catch (Exception e) {
            logger.error("Coinalyze (funding-rate) unexpected error for {}", coinalyzeSymbol, e);
            return FundingRateResponse.invalid();
        }
    }

    /**
     * Historique d'Open Interest (étude Lot 2, item H) : contrairement à {@link #fetchOpenInterest}
     * (valeur ponctuelle), cette méthode remonte plusieurs points {@code [from, to]}/{@code interval}
     * — utilisée par {@code OpenInterestIndicator} pour calculer un delta current/previous (le
     * critère "chute brutale d'OI" ne se lit pas sur une valeur instantanée). Voir avertissement de
     * forme non vérifiée dans {@link OpenInterestHistoryResponse}.
     */
    public OpenInterestHistoryResponse fetchOpenInterestHistory(
            ApiCredentialDTO credential, String coinalyzeSymbol, Instant from, Instant to, String interval) {
        try {
            List<OpenInterestHistoryResponse.Entry> entries = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/open-interest-history")
                            .queryParam("symbols", coinalyzeSymbol)
                            .queryParam("interval", interval)
                            .queryParam("from", from.getEpochSecond())
                            .queryParam("to", to.getEpochSecond())
                            .queryParam("api_key", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(new ParameterizedTypeReference<List<OpenInterestHistoryResponse.Entry>>() {})
                    .timeout(Duration.ofSeconds(20))
                    .block();

            OpenInterestHistoryResponse response = new OpenInterestHistoryResponse();
            response.setEntries(entries);
            response.setValid(true);
            return response;
        } catch (ExternalApiException e) {
            logger.warn("Coinalyze (open-interest-history) unavailable for {}: {}", coinalyzeSymbol, e.getMessage());
            return OpenInterestHistoryResponse.invalid();
        } catch (Exception e) {
            logger.error("Coinalyze (open-interest-history) unexpected error for {}", coinalyzeSymbol, e);
            return OpenInterestHistoryResponse.invalid();
        }
    }

    /**
     * Pas d'endpoint "liquidation courante" côté Coinalyze (contrairement à OI/funding) —
     * seulement un historique : {@code from}/{@code to} bornent la fenêtre glissante récente
     * demandée (voir {@code LiquidationsIndicator}).
     */
    public LiquidationHistoryResponse fetchLiquidations(
            ApiCredentialDTO credential, String coinalyzeSymbol, Instant from, Instant to, String interval) {
        try {
            List<LiquidationHistoryResponse.Entry> entries = getWebClient(credential).get()
                    .uri(uriBuilder -> uriBuilder.path("/liquidation-history")
                            .queryParam("symbols", coinalyzeSymbol)
                            .queryParam("interval", interval)
                            .queryParam("from", from.getEpochSecond())
                            .queryParam("to", to.getEpochSecond())
                            .queryParam("convert_to_usd", "true")
                            .queryParam("api_key", credential.apiKey())
                            .build())
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::toClientException)
                    .onStatus(HttpStatusCode::is5xxServerError, this::toServerException)
                    .bodyToMono(new ParameterizedTypeReference<List<LiquidationHistoryResponse.Entry>>() {})
                    .timeout(Duration.ofSeconds(20))
                    .block();

            LiquidationHistoryResponse response = new LiquidationHistoryResponse();
            response.setEntries(entries);
            response.setValid(true);
            return response;
        } catch (ExternalApiException e) {
            logger.warn("Coinalyze (liquidation-history) unavailable for {}: {}", coinalyzeSymbol, e.getMessage());
            return LiquidationHistoryResponse.invalid();
        } catch (Exception e) {
            logger.error("Coinalyze (liquidation-history) unexpected error for {}", coinalyzeSymbol, e);
            return LiquidationHistoryResponse.invalid();
        }
    }

    private Mono<? extends Throwable> toClientException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Coinalyze 4xx: " + body));
    }

    private Mono<? extends Throwable> toServerException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .map(body -> new ExternalApiException("Coinalyze 5xx: " + body));
    }
}
