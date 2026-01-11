package fr.ses10doigts.tradeIO5.model.dto.decision;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.service.decision.strategy.Strategy;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StrategyKey {
    private final Strategy strategy;
    private final StrategyParameters parameters;
}
