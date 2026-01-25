package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.DoubleRsiStrategy;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.RsiIndicator;

import java.util.Map;

public class IndicatorParametersFactory {

    public static IndicatorParameters buildRsiParams(TimeFrame timeFrame, double period, double sellThreshold, double buyThreshold  ){
        return new IndicatorParameters(
                IndicatorType.RSI,
                Map.of(
                        RsiIndicator.P_PERIOD_NAME, period,
                        DoubleRsiStrategy.P_SELL_THRESHOLD, sellThreshold,
                        DoubleRsiStrategy.P_BUY_THRESHOLD, buyThreshold
                ),                                                                  // Numeric
                Map.of( DoubleRsiStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                            // Boolean
                null
        );
    }

    public static IndicatorParameters buildRsiParams(TimeFrame timeFrame, double period ){
        return new IndicatorParameters(
                IndicatorType.RSI,
                Map.of(
                        RsiIndicator.P_PERIOD_NAME, period
                ),                                                                  // Numeric
                Map.of( DoubleRsiStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                            // Boolean
                null
        );
    }

}
