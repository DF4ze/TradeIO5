package fr.ses10doigts.tradeIO5.model.dto.tree.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class IndicatorSnapshot {

    private final IndicatorType indicatorType;
    private final IndicatorParameters parameters;
    private final IndicatorContext context;
    private final IndicatorResult result;
}