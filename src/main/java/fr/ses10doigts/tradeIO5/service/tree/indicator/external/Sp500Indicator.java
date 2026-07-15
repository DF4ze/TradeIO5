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
 * S&amp;P500, étude "indicateurs-macro-externes" §14 item F. Ticker Twelve Data {@code "SPX"}
 * confirmé <b>verrouillé au palier payant</b> par test réel le 2026-07-15 (404 explicite
 * "available starting with the Grow or Venture plan", même sur {@code NDX}) — bascule sur Yahoo
 * Finance ({@code ^GSPC}, gratuit, sans clé), cf. {@link YahooFinanceQuoteProvider}. {@link
 * DxyIndicator} n'est pas concerné (formule à 6 paires forex, déjà fonctionnelle sur Twelve Data).
 * <p>
 * Même besoin de fraîcheur que documenté à l'origine pour l'endpoint {@code /quote} Twelve Data :
 * les indices actions ne tradent pas 24/7, d'où l'exposition du timestamp de dernière transaction
 * ({@code meta.regularMarketTime} côté Yahoo) dans {@code IndicatorResult.values} sous
 * {@value #V_LAST_TRADE_TIME}, pour qu'un consommateur en aval distingue une valeur fraîche d'une
 * clôture de vendredi soir reconduite tout le week-end.
 * <p>
 * {@code V_PREVIOUS} (étude "nouvelles-opinions-indicateurs-non-branches" §2.1, ajouté pour
 * {@code MacroMarketOpinion}) : mappé depuis {@code YahooFinanceQuote.previousClose}, best-effort
 * (peut être absent, cf. {@link YahooFinanceQuote}) — jamais bloquant pour {@code value}/{@code
 * valid}.
 */
@Component
public class Sp500Indicator implements Indicator {

    static final String SYMBOL = "^GSPC";

    public static final String V_LAST_TRADE_TIME = "lastTradeTime";
    public static final String V_PREVIOUS = "previous";

    private final Logger logger = LoggerFactory.getLogger(Sp500Indicator.class);

    private final YahooFinanceQuoteProvider provider;

    public Sp500Indicator(YahooFinanceQuoteProvider provider) {
        this.provider = provider;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.SP500;
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
                values.put(V_LAST_TRADE_TIME, quote.timestampEpochSeconds().doubleValue());
            }
            if (quote.previousClose() != null) {
                values.put(V_PREVIOUS, quote.previousClose());
            }
            builder.values(values);
        }

        return builder.build();
    }
}
