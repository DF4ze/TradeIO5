package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import lombok.AllArgsConstructor;

public class StrategyParametersFactory {

    /**
     * Construit les 4 {@code IndicatorKey}/{@code IndicatorParameters} (EMA rapide, EMA lente, ADX,
     * RSI) requis par {@link TrendConfirmationStrategy}, ainsi que les seuils ADX/RSI de la
     * Strategy elle-même, portés par {@code StrategyParameters.numericParams} (et non par les
     * {@code IndicatorParameters} de chaque indicateur individuel).
     */
    public static StrategyParameters buildTrendConfirmationStrategyParam(TrendConfirmationParam param){

        IndicatorParameters emaFastParams = IndicatorParametersFactory.buildEmaParams(param.timeFrame, param.emaFastPeriod);
        IndicatorParameters emaSlowParams = IndicatorParametersFactory.buildEmaParams(param.timeFrame, param.emaSlowPeriod);
        IndicatorParameters adxParams = IndicatorParametersFactory.buildAdxParams(param.timeFrame, param.adxPeriod);
        IndicatorParameters rsiParams = IndicatorParametersFactory.buildRsiParams(param.timeFrame, param.rsiPeriod);

        IndicatorKey emaFastKey = new IndicatorKey(IndicatorType.EMA, param.timeFrame, emaFastParams);
        IndicatorKey emaSlowKey = new IndicatorKey(IndicatorType.EMA, param.timeFrame, emaSlowParams);
        IndicatorKey adxKey = new IndicatorKey(IndicatorType.ADX, param.timeFrame, adxParams);
        IndicatorKey rsiKey = new IndicatorKey(IndicatorType.RSI, param.timeFrame, rsiParams);

        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(emaFastKey, emaFastParams);
        params.getIndicatorParameters().put(emaSlowKey, emaSlowParams);
        params.getIndicatorParameters().put(adxKey, adxParams);
        params.getIndicatorParameters().put(rsiKey, rsiParams);

        params.getNumericParams().put(TrendConfirmationStrategy.P_ADX_LOW_THRESHOLD, param.adxLowThreshold);
        params.getNumericParams().put(TrendConfirmationStrategy.P_ADX_HIGH_THRESHOLD, param.adxHighThreshold);
        params.getNumericParams().put(TrendConfirmationStrategy.P_RSI_OVERBOUGHT_THRESHOLD, param.rsiOverboughtThreshold);
        params.getNumericParams().put(TrendConfirmationStrategy.P_RSI_OVERSOLD_THRESHOLD, param.rsiOversoldThreshold);

        return params;
    }

    @AllArgsConstructor
    public static class TrendConfirmationParam {
        TimeFrame timeFrame;
        double emaFastPeriod;
        double emaSlowPeriod;
        double adxPeriod;
        double rsiPeriod;
        double adxLowThreshold;
        double adxHighThreshold;
        double rsiOverboughtThreshold;
        double rsiOversoldThreshold;
    }
}
