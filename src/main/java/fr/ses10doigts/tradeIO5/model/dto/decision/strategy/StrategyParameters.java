package fr.ses10doigts.tradeIO5.model.dto.decision.strategy;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StrategyParameters {
    // Paramètres propres à la stratégie (ex : seuil RSI, coeff pondération)
    private Map<String, Double> numericParams = new HashMap<>();
    private Map<String, String> stringParams = new HashMap<>();
    private Map<String, Boolean> booleanParams = new HashMap<>();

    // Paramètres des indicateurs à utiliser dans cette stratégie
    // clé = IndicatorType
    private Map<IndicatorKey, IndicatorParameters> indicatorParameters = new HashMap<>();
}
