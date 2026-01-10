package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.Indicator;

public record IndicatorExecutionKey(
        IndicatorType indicatorType,
        IndicatorParameters parameters,
        IndicatorContext context
) {
    public static IndicatorExecutionKey of(
            Indicator indicator,
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        return new IndicatorExecutionKey(
                indicator.getType(),
                parameters,
                context
        );
    }
}