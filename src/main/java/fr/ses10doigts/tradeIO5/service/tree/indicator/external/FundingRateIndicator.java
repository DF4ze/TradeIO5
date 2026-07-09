package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.FundingRateResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze.CoinalyzeClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze.CoinalyzeSymbolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Funding Rate des perpétuels (Coinalyze), étude "indicateurs-macro-externes" §12 : troisième
 * entrée nécessaire (avec Open Interest et OBV/volume) pour qualifier un mouvement de prix
 * (spot vs cascade de liquidations à effet de levier) — cette Strategy dérivée est hors scope de
 * ce lot, seul l'indicateur brut est livré ici. Par symbole, comme {@link OpenInterestIndicator}.
 */
@Component
public class FundingRateIndicator implements Indicator {

    private final Logger logger = LoggerFactory.getLogger(FundingRateIndicator.class);

    private final CoinalyzeClient client;
    private final CoinalyzeSymbolResolver symbolResolver;

    public FundingRateIndicator(CoinalyzeClient client, CoinalyzeSymbolResolver symbolResolver) {
        this.client = client;
        this.symbolResolver = symbolResolver;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.FUNDING_RATE;
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
    public IndicatorResult compute(IndicatorContext context, IndicatorParameters parameters) {
        if (context.symbol() == null) {
            logger.warn("{} : aucun symbole dans le contexte, indicateur invalid", getType());
            return IndicatorResult.invalid();
        }

        ApiCredentialDTO credential = parameters.getCredential();

        String coinalyzeSymbol = symbolResolver.resolve(credential, context.symbol());
        if (coinalyzeSymbol == null) {
            return IndicatorResult.invalid();
        }

        FundingRateResponse response = client.fetchFundingRate(credential, coinalyzeSymbol);

        if (!response.isValid() || response.getEntries() == null || response.getEntries().isEmpty()) {
            return IndicatorResult.invalid();
        }

        double value = response.getEntries().getFirst().getValue();

        return IndicatorResult.builder()
                .valid(true)
                .value(value)
                .build();
    }
}
