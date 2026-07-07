package fr.ses10doigts.tradeIO5.service.tree.helper;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.TrendConfirmationStrategy;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.AdxIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.EmaIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.RsiIndicator;

import java.util.Map;

public class IndicatorParametersFactory {

    public static IndicatorParameters buildRsiParams(TimeFrame timeFrame, double period ){
        return new IndicatorParameters(
                IndicatorType.RSI,
                Map.of(
                        RsiIndicator.P_PERIOD_NAME, period
                ),                                                                  // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                            // Boolean
                null
        );
    }

    public static IndicatorParameters buildEmaParams(TimeFrame timeFrame, double period){
        return new IndicatorParameters(
                IndicatorType.EMA,
                Map.of(
                        EmaIndicator.P_PERIOD_NAME, period
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                null
        );
    }

    public static IndicatorParameters buildAdxParams(TimeFrame timeFrame, double period){
        return new IndicatorParameters(
                IndicatorType.ADX,
                Map.of(
                        AdxIndicator.P_PERIOD_NAME, period
                ),                                                                              // Numeric
                Map.of( TrendConfirmationStrategy.P_TIME_FRAME_NAME, timeFrame.toString()),    // String
                Map.of(),                                                                        // Boolean
                null
        );
    }

}
