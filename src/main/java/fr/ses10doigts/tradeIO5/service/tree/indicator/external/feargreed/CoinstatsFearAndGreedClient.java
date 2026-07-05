package fr.ses10doigts.tradeIO5.service.tree.indicator.external.feargreed;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.FearAndGreedResponse;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.AbstractExternalIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CoinstatsFearAndGreedClient extends AbstractExternalIndicator implements FearAndGreedProvider{
    private final Logger logger = LoggerFactory.getLogger(CoinstatsFearAndGreedClient.class);


    @Override
    public FearAndGreedResponse fetch(ApiCredentialDTO credential) {

        FearAndGreedResponse response;
        try {
            response = getFearAndGreed(credential);

        }catch (ExternalApiException e){
            logger.warn("{} unavailable: {}", credential.provider(), e.getMessage());
            return FearAndGreedResponse.invalid();

        } catch (Exception e) {
            logger.error("{} unexpected error", credential.provider(), e);
            return FearAndGreedResponse.invalid();
        }
         return response;
    }

    private FearAndGreedResponse getFearAndGreed(ApiCredentialDTO credential) {

        return getWebClient(credential).get()
                .uri("/insights/fear-and-greed") // TODO : Parametrize
                .header("X-API-KEY", credential.apiKey())
                .retrieve()
                .onStatus(
                        HttpStatusCode::is4xxClientError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException(
                                        "CoinStats 4xx: " + body))
                )
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> response.bodyToMono(String.class)
                                .map(body -> new ExternalApiException(
                                        "CoinStats 5xx: " + body))
                )
                .bodyToMono(FearAndGreedResponse.class)
                // 5s s'est avéré trop court en pratique (timeout systématique observé alors
                // qu'un curl direct répond en <1s) : 5s ne laisse pas de marge pour une
                // résolution DNS/handshake TLS un peu lente. 20s pour absorber ça sans pour
                // autant bloquer indéfiniment un appel MCP.
                .timeout(Duration.ofSeconds(20))
                .block();
    }

}