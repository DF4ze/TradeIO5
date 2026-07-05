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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ADX (Average Directional Index), méthode standard de Wilder.
 *
 * Ne dépend d'aucun autre indicateur : se calcule directement sur les
 * MarketData (high/low/close) du contexte, comme RsiIndicator.
 */
@Component
public class AdxIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(AdxIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    public static final String V_PLUS_DI = "+DI";
    public static final String V_MINUS_DI = "-DI";

    @Override
    public IndicatorType getType() {
        return IndicatorType.ADX;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        int nbRequied = 0;
        if (parameters.getNumeric(P_PERIOD_NAME) != null)
            nbRequied = 2 * parameters.getNumeric(P_PERIOD_NAME).intValue();
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

        if (period <= 0 || data.size() < 2 * period) {
            logger.error("Invalid context : MarketData size too short for ADX");
            return IndicatorResult.invalid();
        }

        int n = data.size();

        // +DM, -DM, TR par bougie, à partir de la 2e bougie (nécessite la bougie précédente)
        BigDecimal[] plusDm = new BigDecimal[n - 1];
        BigDecimal[] minusDm = new BigDecimal[n - 1];
        BigDecimal[] tr = new BigDecimal[n - 1];

        for (int i = 1; i < n; i++) {
            MarketData curr = data.get(i);
            MarketData prev = data.get(i - 1);

            if (curr.getHigh() == null || curr.getLow() == null || curr.getClose() == null
                    || prev.getHigh() == null || prev.getLow() == null || prev.getClose() == null) {
                logger.error("Invalid context : high/low/close required for ADX");
                return IndicatorResult.invalid();
            }

            BigDecimal upMove = curr.getHigh().subtract(prev.getHigh());
            BigDecimal downMove = prev.getLow().subtract(curr.getLow());

            BigDecimal pDm = (upMove.signum() > 0 && upMove.compareTo(downMove) > 0) ? upMove : BigDecimal.ZERO;
            BigDecimal mDm = (downMove.signum() > 0 && downMove.compareTo(upMove) > 0) ? downMove : BigDecimal.ZERO;

            BigDecimal highLow = curr.getHigh().subtract(curr.getLow());
            BigDecimal highPrevClose = curr.getHigh().subtract(prev.getClose()).abs();
            BigDecimal lowPrevClose = curr.getLow().subtract(prev.getClose()).abs();

            BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);

            plusDm[i - 1] = pDm;
            minusDm[i - 1] = mDm;
            tr[i - 1] = trueRange;
        }

        BigDecimal bdPeriod = BigDecimal.valueOf(period);

        // Lissage de Wilder : première valeur = somme des `period` premières valeurs,
        // correspond à la position (period - 1) dans les tableaux +DM/-DM/TR.
        BigDecimal smoothedPlusDm = sum(plusDm, 0, period);
        BigDecimal smoothedMinusDm = sum(minusDm, 0, period);
        BigDecimal smoothedTr = sum(tr, 0, period);

        List<Double> dxValues = new ArrayList<>();
        Double lastPlusDi = null;
        Double lastMinusDi = null;

        for (int pos = period - 1; pos < plusDm.length; pos++) {
            if (pos > period - 1) {
                // valeur_précédente - valeur_précédente/period + valeur_courante
                smoothedPlusDm = smoothedPlusDm
                        .subtract(smoothedPlusDm.divide(bdPeriod, MathContext.DECIMAL64))
                        .add(plusDm[pos]);
                smoothedMinusDm = smoothedMinusDm
                        .subtract(smoothedMinusDm.divide(bdPeriod, MathContext.DECIMAL64))
                        .add(minusDm[pos]);
                smoothedTr = smoothedTr
                        .subtract(smoothedTr.divide(bdPeriod, MathContext.DECIMAL64))
                        .add(tr[pos]);
            }

            if (smoothedTr.signum() == 0) {
                logger.error("Invalid context : smoothed TR is zero, cannot compute ADX");
                return IndicatorResult.invalid();
            }

            double plusDi = BigDecimal.valueOf(100)
                    .multiply(smoothedPlusDm)
                    .divide(smoothedTr, MathContext.DECIMAL64)
                    .doubleValue();
            double minusDi = BigDecimal.valueOf(100)
                    .multiply(smoothedMinusDm)
                    .divide(smoothedTr, MathContext.DECIMAL64)
                    .doubleValue();

            double diSum = plusDi + minusDi;
            double dx = diSum == 0 ? 0 : 100 * Math.abs(plusDi - minusDi) / diSum;

            dxValues.add(dx);
            lastPlusDi = plusDi;
            lastMinusDi = minusDi;
        }

        if (dxValues.size() < period) {
            logger.error("Invalid context : not enough DX values to smooth ADX");
            return IndicatorResult.invalid();
        }

        // ADX = moyenne de Wilder de DX : première valeur = moyenne simple des `period` premiers DX,
        // puis lissage Wilder ensuite (forme moyenne équivalente : (prev*(period-1) + courant) / period).
        double adx = dxValues.subList(0, period).stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

        for (int k = period; k < dxValues.size(); k++) {
            adx = (adx * (period - 1) + dxValues.get(k)) / period;
        }

        logger.debug("{} indicator with {} = {} on TF {} returns {}", getType(), P_PERIOD_NAME, period, series.getTimeFrame(), adx);

        return IndicatorResult.builder()
                .value(adx)
                .values(Map.of(
                        V_PLUS_DI, lastPlusDi,
                        V_MINUS_DI, lastMinusDi
                ))
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
