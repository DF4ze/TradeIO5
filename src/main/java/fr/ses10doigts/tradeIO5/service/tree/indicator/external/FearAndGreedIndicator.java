package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.exceptions.ExternalApiException;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.external.FearAndGreedResponse;
import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.feargreed.FearAndGreedProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FearAndGreedIndicator implements Indicator {

    private final FearAndGreedProvider provider;

    public FearAndGreedIndicator(FearAndGreedProvider provider) {
        this.provider = provider;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.FEAR_GREED;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(AbstractExternalIndicator.P_CREDENTIAL);
    }

    @Override
    public IndicatorResult compute(
        IndicatorContext context,
        IndicatorParameters parameters
    ) {
        try {
            ApiCredentialDTO credential = parameters.getCredential();

            FearAndGreedResponse response = provider.fetch(credential);

            return IndicatorResult.builder()
                    .valid(true)
                    .values(
                            Map.of(
                                    "now", (double)response.getNow().getValue(),
                                    "yesterday", (double)response.getYesterday().getValue(),
                                    "lastWeek", (double)response.getLastWeek().getValue()
                            )
                    )
                    .build()  ;

        } catch (ExternalApiException e) {
            return IndicatorResult.invalid();
        }
    }

}
