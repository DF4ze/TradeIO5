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
import java.util.Map;

/**
 * Bollinger Bands : SMA(period) des clôtures +/- (stdDevMultiplier x écart-type des
 * clôtures sur la même fenêtre).
 *
 * Ne dépend d'aucun autre indicateur : se calcule directement sur les MarketData
 * (close) du contexte, comme {@link SmaIndicator}.
 */
@Component
public class BollingerIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(BollingerIndicator.class);

    public static final String P_PERIOD_NAME = "period";
    public static final String P_STD_DEV_MULTIPLIER_NAME = "stdDevMultiplier";

    public static final String V_UPPER = "upper";
    public static final String V_MIDDLE = "middle";
    public static final String V_LOWER = "lower";

    @Override
    public IndicatorType getType() {
        return IndicatorType.BOLLINGER;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        int nbRequied = 0;
        if (parameters.getNumeric(P_PERIOD_NAME) != null)
            nbRequied = parameters.getNumeric(P_PERIOD_NAME).intValue();
        return nbRequied;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(P_PERIOD_NAME, P_STD_DEV_MULTIPLIER_NAME);
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        double periodD = parameters.getNumeric(P_PERIOD_NAME);
        int period = (int) periodD;
        double multiplier = parameters.getNumeric(P_STD_DEV_MULTIPLIER_NAME);

        MarketDataset series = context.marketDataset();
        List<MarketData> data = series.getMarketDatas();

        if (period <= 0 || data.size() < period) {
            logger.error("Invalid context : MarketData size too short for Bollinger");
            return IndicatorResult.invalid();
        }

        List<MarketData> window = data.subList(data.size() - period, data.size());
        BigDecimal bdPeriod = BigDecimal.valueOf(period);

        BigDecimal sum = BigDecimal.ZERO;
        for (MarketData candle : window) {
            if (candle.getClose() == null) {
                logger.error("Invalid context : close required for Bollinger");
                return IndicatorResult.invalid();
            }
            sum = sum.add(candle.getClose());
        }
        BigDecimal sma = sum.divide(bdPeriod, MathContext.DECIMAL64);

        BigDecimal variance = BigDecimal.ZERO;
        for (MarketData candle : window) {
            BigDecimal diff = candle.getClose().subtract(sma);
            variance = variance.add(diff.multiply(diff));
        }
        variance = variance.divide(bdPeriod, MathContext.DECIMAL64);

        double stdDev = Math.sqrt(variance.doubleValue());
        double middle = sma.doubleValue();
        double upper = middle + multiplier * stdDev;
        double lower = middle - multiplier * stdDev;

        logger.debug("{} indicator with {}={}, {}={} on TF {} returns middle={}, upper={}, lower={}",
                getType(), P_PERIOD_NAME, period, P_STD_DEV_MULTIPLIER_NAME, multiplier, series.getTimeFrame(), middle, upper, lower);

        return IndicatorResult.builder()
                .value(middle)
                .min(lower)
                .max(upper)
                .values(Map.of(
                        V_UPPER, upper,
                        V_MIDDLE, middle,
                        V_LOWER, lower
                ))
                .valid(true)
                .build();
    }
}
