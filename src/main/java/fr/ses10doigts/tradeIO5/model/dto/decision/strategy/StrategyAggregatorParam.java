package fr.ses10doigts.tradeIO5.model.dto.decision.strategy;

import fr.ses10doigts.tradeIO5.service.decision.strategy.Strategy;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StrategyAggregatorParam {
    private Strategy strategy;
    private StrategyParameters parameters;
}
