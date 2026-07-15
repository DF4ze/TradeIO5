package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo.YahooFinanceQuote;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.yahoo.YahooFinanceQuoteProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Nasdaq (Composite), étude "indicateurs-macro-externes" §14 item F. Ticker Twelve Data
 * {@code "IXIC"} confirmé <b>invalide</b> par test réel le 2026-07-15 ("symbol or figi parameter
 * is missing or invalid" — pas même reconnu par le catalogue Twelve Data, contrairement à
 * {@code SPX}/{@code NDX} qui existent mais sont verrouillés au palier payant) — bascule sur
 * Yahoo Finance ({@code ^IXIC}, gratuit, sans clé), cf. {@link YahooFinanceQuoteProvider}. Même
 * choix de source et même fraîcheur exposée que {@link Sp500Indicator} (sous
 * {@value Sp500Indicator#V_LAST_TRADE_TIME}) — voir la javadoc de cette classe pour le
 * raisonnement complet, non dupliqué ici.
 */
@Component
public class NasdaqIndicator implements Indicator {

    static final String SYMBOL = "^IXIC";

    private final Logger logger = LoggerFactory.getLogger(NasdaqIndicator.class);

    private final YahooFinanceQuoteProvider provider;

    public NasdaqIndicator(YahooFinanceQuoteProvider provider) {
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

        Map<String, YahooFinanceQuote> quotes = provider.fetchQuotes(credential, List.of(SYMBOL));
        YahooFinanceQuote quote = quotes.get(SYMBOL);

        if (quote == null) {
            logger.warn("{} : quote Yahoo Finance manquante/invalide pour {}", getType(), SYMBOL);
            return IndicatorResult.invalid();
        }

        IndicatorResult.IndicatorResultBuilder builder = IndicatorResult.builder()
                .valid(true)
                .value(quote.price());

        if (quote.timestampEpochSeconds() != null || quote.previousClose() != null) {
            Map<String, Double> values = new java.util.HashMap<>();
            if (quote.timestampEpochSeconds() != null) {
                values.put(Sp500Indicator.V_LAST_TRADE_TIME, quote.timestampEpochSeconds().doubleValue());
            }
            if (quote.previousClose() != null) {
                values.put(Sp500Indicator.V_PREVIOUS, quote.previousClose());
            }
            builder.values(values);
        }

        return builder.build();
    }
}
