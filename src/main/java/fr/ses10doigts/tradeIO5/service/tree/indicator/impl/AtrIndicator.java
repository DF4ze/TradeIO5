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
import java.math.MathContext;
import java.util.List;

/**
 * ATR (Average True Range), lissage de Wilder.
 *
 * Ne dépend d'aucun autre indicateur : se calcule directement sur les
 * MarketData (high/low/close) du contexte, comme {@link AdxIndicator} dont
 * il reprend le calcul du True Range (mais sans la couche +DI/-DI/DX).
 */
@Component
public class AtrIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(AtrIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    @Override
    public IndicatorType getType() {
        return IndicatorType.ATR;
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
            logger.error("Invalid context : MarketData size too short for ATR");
            return IndicatorResult.invalid();
        }

        int n = data.size();

        // True Range par bougie, à partir de la 2e bougie (nécessite la bougie précédente) :
        // même calcul que AdxIndicator.
        BigDecimal[] tr = new BigDecimal[n - 1];

        for (int i = 1; i < n; i++) {
            MarketData curr = data.get(i);
            MarketData prev = data.get(i - 1);

            if (curr.getHigh() == null || curr.getLow() == null || curr.getClose() == null
                    || prev.getClose() == null) {
                logger.error("Invalid context : high/low/close required for ATR");
                return IndicatorResult.invalid();
            }

            BigDecimal highLow = curr.getHigh().subtract(curr.getLow());
            BigDecimal highPrevClose = curr.getHigh().subtract(prev.getClose()).abs();
            BigDecimal lowPrevClose = curr.getLow().subtract(prev.getClose()).abs();

            tr[i - 1] = highLow.max(highPrevClose).max(lowPrevClose);
        }

        BigDecimal bdPeriod = BigDecimal.valueOf(period);

        // Lissage de Wilder : première valeur = moyenne simple des `period` premiers TR,
        // puis lissage progressif pour chaque TR supplémentaire disponible (forme moyenne
        // équivalente : (prev*(period-1) + courant) / period, même patron que AdxIndicator).
        BigDecimal atr = sum(tr, 0, period).divide(bdPeriod, MathContext.DECIMAL64);

        for (int pos = period; pos < tr.length; pos++) {
            atr = atr.multiply(bdPeriod.subtract(BigDecimal.ONE))
                    .add(tr[pos])
                    .divide(bdPeriod, MathContext.DECIMAL64);
        }

        logger.debug("{} indicator with {} = {} on TF {} returns {}", getType(), P_PERIOD_NAME, period, series.getTimeFrame(), atr);

        return IndicatorResult.builder()
                .value(atr.doubleValue())
                .valid(true)
                .build();
    }

    private BigDecimal sum(BigDecimal[] arr, int fromInclusive, int toExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = fromInclusive; i < toExclusive; i++) {
            total = total.add(arr[i]);
        }
        return total;
    }
}
