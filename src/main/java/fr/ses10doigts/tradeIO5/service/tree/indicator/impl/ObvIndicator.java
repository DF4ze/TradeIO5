package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * OBV (On-Balance Volume) : cumule le volume des bougies dont la clôture progresse dans
 * le sens du mouvement (ajouté si clôture &gt; clôture précédente, retranché si clôture
 * &lt; clôture précédente, inchangé si égalité), sur les `period` dernières bougies du
 * MarketDataset fourni.
 *
 * Ne dépend d'aucun autre indicateur : se calcule directement sur les MarketData
 * (close/volume) du contexte. Contrairement à un OBV "depuis l'origine du marché", ce
 * calcul est borné à la fenêtre de MarketDataset disponible (comme tous les autres
 * indicateurs de ce projet, qui n'ont pas de mémoire entre deux exécutions) : la valeur
 * absolue n'est donc pas comparable à un OBV calculé ailleurs avec un historique
 * différent, seule sa variation d'une exécution à l'autre est signifiante.
 */
@Component
public class ObvIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(ObvIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    @Override
    public IndicatorType getType() {
        return IndicatorType.OBV;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        int nbRequied = 0;
        if (parameters.getNumeric(P_PERIOD_NAME) != null)
            nbRequied = parameters.getNumeric(P_PERIOD_NAME).intValue() + 1;
        return nbRequied;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(P_PERIOD_NAME);
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        double periodD = parameters.getNumeric(P_PERIOD_NAME);
        int period = (int) periodD;

        MarketDataset series = context.marketDataset();
        List<MarketData> data = series.getMarketDatas();

        if (period <= 0 || data.size() < period + 1) {
            logger.error("Invalid context : MarketData size too short for OBV");
            return IndicatorResult.invalid();
        }

        List<MarketData> window = data.subList(data.size() - (period + 1), data.size());

        MarketData first = window.get(0);
        if (first.getVolume() == null || first.getClose() == null) {
            logger.error("Invalid context : close/volume required for OBV");
            return IndicatorResult.invalid();
        }

        BigDecimal obv = first.getVolume();

        for (int i = 1; i < window.size(); i++) {
            MarketData curr = window.get(i);
            MarketData prev = window.get(i - 1);

            if (curr.getVolume() == null || curr.getClose() == null || prev.getClose() == null) {
                logger.error("Invalid context : close/volume required for OBV");
                return IndicatorResult.invalid();
            }

            int direction = curr.getClose().compareTo(prev.getClose());
            if (direction > 0) {
                obv = obv.add(curr.getVolume());
            } else if (direction < 0) {
                obv = obv.subtract(curr.getVolume());
            }
        }

        logger.debug("{} indicator with {} = {} on TF {} returns {}", getType(), P_PERIOD_NAME, period, series.getTimeFrame(), obv);

        return IndicatorResult.builder()
                .value(obv.doubleValue())
                .valid(true)
                .build();
    }
}
