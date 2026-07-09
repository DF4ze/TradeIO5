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
 * S&amp;P500, étude "indicateurs-macro-externes" §14 item F. Ticker Twelve Data {@code "SPX"} <b>à
 * confirmer</b> (non vérifié contre un appel réel — pas de clé disponible au moment de cette
 * implémentation ; Twelve Data peut exposer l'indice sous {@code SPX}, {@code GSPC} ou un symbole
 * spécifique à son catalogue, cf. leur outil de recherche de symboles si {@code SPX} ne répond
 * pas).
 * <p>
 * <b>Choix documenté (endpoint distinct de l'item E)</b> : contrairement à {@link DxyIndicator}
 * (qui utilise {@code /price}, sans timestamp), cet indicateur utilise
 * {@link TwelveDataQuoteProvider#fetchQuotes} (endpoint {@code /quote}) car les indices actions ne
 * tradent pas 24/7 (point d'attention explicite de l'étude) : {@code /price} ne porte aucune
 * donnée de fraîcheur exploitable, alors que {@code /quote} porte un timestamp de dernière
 * transaction — exposé ici dans {@code IndicatorResult.values} sous {@value #V_LAST_TRADE_TIME},
 * pour qu'un consommateur en aval distingue une valeur fraîche d'une clôture de vendredi soir
 * reconduite tout le week-end.
 */
@Component
public class Sp500Indicator implements Indicator {

    static final String SYMBOL = "SPX";

    public static final String V_LAST_TRADE_TIME = "lastTradeTime";

    private final Logger logger = LoggerFactory.getLogger(Sp500Indicator.class);

    private final TwelveDataQuoteProvider provider;

    public Sp500Indicator(TwelveDataQuoteProvider provider) {
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
            builder.values(Map.of(V_LAST_TRADE_TIME, quote.timestampEpochSeconds().doubleValue()));
        }

        return builder.build();
    }
}
