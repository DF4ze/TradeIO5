package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class IndicatorSnapshot {

    private final IndicatorType indicatorCode;
    private final IndicatorParameters parameters;
    private final IndicatorContext context;
    private final IndicatorResult result;

}