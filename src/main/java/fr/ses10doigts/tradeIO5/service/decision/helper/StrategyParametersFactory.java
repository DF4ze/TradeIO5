package fr.ses10doigts.tradeIO5.service.decision.helper;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import lombok.AllArgsConstructor;

public class StrategyParametersFactory {

    public static StrategyParameters buildDoubleRsiStrategyParam(RsiParam slow, RsiParam fast){

        IndicatorParameters slowRsiParams = IndicatorParametersFactory.buildRsiParams(slow.timeFrame, slow.period, slow.sellThreshold, slow.buyThreshold);
        IndicatorParameters fastRsiParams = IndicatorParametersFactory.buildRsiParams(fast.timeFrame, fast.period, fast.sellThreshold, fast.buyThreshold);

        IndicatorKey slowRsiKey = new IndicatorKey(IndicatorType.RSI, slow.timeFrame, slowRsiParams);
        IndicatorKey fastRsiKey = new IndicatorKey(IndicatorType.RSI, fast.timeFrame, fastRsiParams);

        // Building strategy params
        StrategyParameters params = new StrategyParameters();
        params.getIndicatorParameters().put(slowRsiKey, slowRsiParams);
        params.getIndicatorParameters().put(fastRsiKey, fastRsiParams);

        return params;
    }

    @AllArgsConstructor
    public static class RsiParam {
        TimeFrame timeFrame;
        double period;
        double sellThreshold;
        double buyThreshold;
    }
}
