package fr.ses10doigts.tradeIO5.model.dto.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@AllArgsConstructor
@EqualsAndHashCode
public class IndicatorKey {
    private IndicatorType type;
    private TimeFrame timeFrame;
    private IndicatorParameters params;
}
