package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.Indicator;

public record IndicatorExecutionKey(
        IndicatorCode indicatorCode,
        IndicatorParameters parameters,
        IndicatorContext context
) {
    public static IndicatorExecutionKey of(
            Indicator indicator,
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        return new IndicatorExecutionKey(
                indicator.getCode(),
                parameters,
                context
        );
    }
}