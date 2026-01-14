package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.external.FearAndGreedResponse;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class FearAndGreedIndicator extends AbstractExternalIndicator {
    private final Logger logger = LoggerFactory.getLogger(FearAndGreedIndicator.class);


    @Override
    public IndicatorType getType() {
        return IndicatorType.FEAR_GREED;
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        FearAndGreedResponse response;
        try {
            response = getFearAndGreed();

        }catch (ExternalApiException e){
            logger.warn("{} unavailable: {}", getType(), e.getMessage());
            return IndicatorResult.invalid();

        } catch (Exception e) {
            logger.error("{} unexpected error", getType(), e);
            return IndicatorResult.invalid();
        }
         return IndicatorResult.builder()
                .value((double) response.getNow().getValue())
                .valid(true)
                .build();
    }

    private FearAndGreedResponse getFearAndGreed() { // FIXME: FIx all of this!
        ApiCredential cred = ApiCredential.builder()
                .webProvider(
                        WebProvider.builder()
                        .apiBaseUrl("https://openapiv1.coinstats.app")
                        .build()
                )
                .apiKey("v8ZVIcChU67x9vyMi1Ts0fzyNgwbetIHiNodpD4UonI=")
                .build();

        return getWebClient(cred).get()
                .uri("/insights/fear-and-greed") // TODO : Parametrize
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
                .timeout(Duration.ofSeconds(5))
                .block();
    }

}