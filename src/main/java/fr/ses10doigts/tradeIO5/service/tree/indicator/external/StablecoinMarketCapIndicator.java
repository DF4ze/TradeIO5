package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.StablecoinMarketCapResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.stablecoin.StablecoinMarketCapProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Capitalisation totale des stablecoins peggés USD (DefiLlama), proxy d'injection/retrait de
 * liquidités crypto (étude "indicateurs-macro-externes", §7). Valeur externe unique, sans notion
 * de symbole — même patron que {@link FearAndGreedIndicator}.
 */
@Component
public class StablecoinMarketCapIndicator implements Indicator {

    public static final String V_TOTAL = "total";
    public static final String V_TOTAL_PREV_DAY = "totalPrevDay";
    public static final String V_TOTAL_PREV_WEEK = "totalPrevWeek";
    public static final String V_TOTAL_PREV_MONTH = "totalPrevMonth";

    private final StablecoinMarketCapProvider provider;

    public StablecoinMarketCapIndicator(StablecoinMarketCapProvider provider) {
        this.provider = provider;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.STABLECOIN_MARKET_CAP;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        return 0;
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
        ApiCredentialDTO credential = parameters.getCredential();

        StablecoinMarketCapResponse response = provider.fetch(credential);

        if (!response.isValid()) {
            return IndicatorResult.invalid();
        }

        return IndicatorResult.builder()
                .valid(true)
                .value(response.getTotal())
                .values(
                        Map.of(
                                V_TOTAL, response.getTotal(),
                                V_TOTAL_PREV_DAY, response.getTotalPrevDay(),
                                V_TOTAL_PREV_WEEK, response.getTotalPrevWeek(),
                                V_TOTAL_PREV_MONTH, response.getTotalPrevMonth()
                        )
                )
                .build();
    }
}
