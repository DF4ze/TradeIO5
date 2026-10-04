package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.trend.LinearRegressionCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.LinearRegressionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptateur fin autour de {@link LinearRegressionCalculator} (couche calculateur, service pur) —
 * même patron que {@link SwingStructureIndicator}/{@code SwingStructureCalculator}. Piste étudiée
 * à la demande de Clem (2026-09-19) : 3 instances de cet indicateur (typiquement period=7/14/30
 * sur D1), combinées par {@code RegressiveTrendStrategy}, en complément (pas en remplacement) de
 * SWING_STRUCTURE.
 * <p>
 * {@code value} = pente normalisée (fraction du prix moyen de la fenêtre par bougie, signée) —
 * comparable entre actifs/volatilités, contrairement à une pente en prix brut. {@code values}
 * expose en plus {@code r2} (qualité de l'ajustement, 0=bruit/range, 1=tendance parfaite),
 * {@code slope} (pente brute) et {@code regressionValue} (valeur de la droite ajustée à la
 * dernière bougie — utile pour tracer la courbe sur un graphique).
 */
@Component
public class LinearRegressionIndicator implements Indicator {

    private final Logger logger = LoggerFactory.getLogger(LinearRegressionIndicator.class);

    public static final String P_PERIOD_NAME = "period";
    public static final String V_R2 = "r2";
    public static final String V_SLOPE = "slope";
    public static final String V_REGRESSION_VALUE = "regressionValue";

    private final LinearRegressionCalculator calculator = new LinearRegressionCalculator();

    @Override
    public IndicatorType getType() {
        return IndicatorType.LINEAR_REGRESSION;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        Double period = parameters.getNumeric(P_PERIOD_NAME);
        return period == null ? 0 : period.intValue();
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
        Double periodParam = parameters.getNumeric(P_PERIOD_NAME);
        if (periodParam == null || periodParam.intValue() < 2) {
            logger.error("Invalid parameters : missing/invalid '{}' parameter", P_PERIOD_NAME);
            return IndicatorResult.invalid();
        }
        int period = periodParam.intValue();

        MarketDataset dataset = context.marketDataset();
        List<MarketData> candles = dataset == null ? null : dataset.getMarketDatas();

        if (candles == null || candles.size() < period) {
            logger.error("Invalid context : MarketData size too short for period {}", period);
            return IndicatorResult.invalid();
        }

        LinearRegressionResult result = calculator.compute(candles, period);

        Map<String, Double> values = new HashMap<>();
        values.put(V_R2, result.r2());
        values.put(V_SLOPE, result.slope());
        values.put(V_REGRESSION_VALUE, result.regressionValue());

        logger.debug("{} (period={}) on TF {} : normalizedSlope={}, r2={}",
                getType(), period, dataset.getTimeFrame(), result.normalizedSlope(), result.r2());

        return IndicatorResult.builder()
                .value(result.normalizedSlope())
                .values(values)
                .valid(true)
                .build();
    }
}
