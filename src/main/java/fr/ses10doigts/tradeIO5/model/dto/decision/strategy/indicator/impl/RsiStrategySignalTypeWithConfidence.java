package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.impl;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RsiStrategySignalTypeWithConfidence {
    private final SignalType signal;
    private final double confidence;

}
