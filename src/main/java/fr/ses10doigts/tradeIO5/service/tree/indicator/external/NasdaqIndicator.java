package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuote;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuoteProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Nasdaq (Composite), étude "indicateurs-macro-externes" §14 item F. Ticker Twelve Data
 * {@code "IXIC"} <b>à confirmer</b> (non vérifié contre un appel réel, cf. {@link Sp500Indicator}
 * pour le même avertissement). Même choix d'endpoint {@code /quote} que {@link Sp500Indicator}
 * (fraîcheur exposée sous {@value Sp500Indicator#V_LAST_TRADE_TIME}) — voir la javadoc de cette
 * classe pour le raisonnement complet, non dupliqué ici.
 */
@Component
public class NasdaqIndicator implements Indicator {

    static final String SYMBOL = "IXIC";

    private final Logger logger = LoggerFactory.getLogger(NasdaqIndicator.class);

    private final TwelveDataQuoteProvider provider;

    public NasdaqIndicator(TwelveDataQuoteProvider provider) {
        this.provider = provider;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.NASDAQ;
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
        ApiCredentialDTO credential = parameters.getCredential();

        Map<String, TwelveDataQuote> quotes = provider.fetchQuotes(credential, List.of(SYMBOL));
        TwelveDataQuote quote = quotes.get(SYMBOL);

        if (quote == null) {
            logger.warn("{} : quote Twelve Data manquante/invalide pour {}", getType(), SYMBOL);
            return IndicatorResult.invalid();
        }

        IndicatorResult.IndicatorResultBuilder builder = IndicatorResult.builder()
                .valid(true)
                .value(quote.price());

        if (quote.timestampEpochSeconds() != null) {
            builder.values(Map.of(Sp500Indicator.V_LAST_TRADE_TIME, quote.timestampEpochSeconds().doubleValue()));
        }

        return builder.build();
    }
}
