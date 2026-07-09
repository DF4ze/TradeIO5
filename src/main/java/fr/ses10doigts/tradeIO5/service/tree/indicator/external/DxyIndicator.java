package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.twelvedata.TwelveDataQuoteProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DXY (indice dollar), étude "indicateurs-macro-externes" §14 item E : pas de ticker DXY fiable
 * garanti sur le palier gratuit Twelve Data — calcul synthétique à partir de la formule officielle
 * et de 6 paires forex :
 * <pre>
 * DXY = 50.14348112 × EURUSD^-0.576 × USDJPY^0.136 × GBPUSD^-0.119
 *                    × USDCAD^0.091 × USDSEK^0.042 × USDCHF^0.036
 * </pre>
 * Si une seule des 6 paires manque/est invalide, l'indicateur retourne {@link IndicatorResult#invalid()}
 * plutôt que de calculer avec une valeur par défaut arbitraire : une formule à 6 facteurs
 * multiplicatifs avec un facteur manquant ne peut pas être silencieusement approximée.
 */
@Component
public class DxyIndicator implements Indicator {

    // Ordre sans importance pour le calcul (map par symbole), mais fixé ici pour la lisibilité
    // des tests / logs.
    static final String EURUSD = "EUR/USD";
    static final String USDJPY = "USD/JPY";
    static final String GBPUSD = "GBP/USD";
    static final String USDCAD = "USD/CAD";
    static final String USDSEK = "USD/SEK";
    static final String USDCHF = "USD/CHF";

    static final List<String> PAIRS = List.of(EURUSD, USDJPY, GBPUSD, USDCAD, USDSEK, USDCHF);

    private static final double BASE = 50.14348112;

    private final Logger logger = LoggerFactory.getLogger(DxyIndicator.class);

    private final TwelveDataQuoteProvider provider;

    public DxyIndicator(TwelveDataQuoteProvider provider) {
        this.provider = provider;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.DXY;
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

        Map<String, Double> prices = provider.fetchPrices(credential, PAIRS);

        Double dxy = computeDxy(prices);
        if (dxy == null) {
            logger.warn("{} : au moins une des 6 paires forex manque/est invalide ({}), indicateur invalid",
                    getType(), prices.keySet());
            return IndicatorResult.invalid();
        }

        return IndicatorResult.builder()
                .valid(true)
                .value(dxy)
                .build();
    }

    /**
     * Logique pure de calcul, isolée de l'appel réseau pour être testable en unitaire sans clé
     * Twelve Data. Retourne {@code null} si une des 6 paires manque de la map (jamais une
     * approximation avec une valeur par défaut).
     */
    static Double computeDxy(Map<String, Double> prices) {
        if (prices == null) {
            return null;
        }
        for (String pair : PAIRS) {
            if (!prices.containsKey(pair) || prices.get(pair) == null) {
                return null;
            }
        }

        double eurusd = prices.get(EURUSD);
        double usdjpy = prices.get(USDJPY);
        double gbpusd = prices.get(GBPUSD);
        double usdcad = prices.get(USDCAD);
        double usdsek = prices.get(USDSEK);
        double usdchf = prices.get(USDCHF);

        return BASE
                * Math.pow(eurusd, -0.576)
                * Math.pow(usdjpy, 0.136)
                * Math.pow(gbpusd, -0.119)
                * Math.pow(usdcad, 0.091)
                * Math.pow(usdsek, 0.042)
                * Math.pow(usdchf, 0.036);
    }
}
