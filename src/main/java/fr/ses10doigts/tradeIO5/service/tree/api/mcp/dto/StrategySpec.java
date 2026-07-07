package fr.ses10doigts.tradeIO5.service.tree.api.mcp.dto;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;

import java.util.List;
import java.util.Map;

/**
 * Description "plate" et LLM-friendly d'une stratégie à utiliser dans une Opinion.
 *
 * @param strategyType   type de la stratégie (ex: ENTRY, EXIT, RISK) — résolu via StrategyRegistry
 * @param indicators     indicateurs requis par la stratégie (ex: EMA rapide + EMA lente + ADX + RSI pour TrendConfirmationStrategy)
 * @param numericParams  paramètres numériques propres à la stratégie
 * @param stringParams   paramètres texte propres à la stratégie
 * @param booleanParams  paramètres booléens propres à la stratégie
 */
public record StrategySpec(
        StrategyType strategyType,
        List<IndicatorSpec> indicators,
        Map<String, Double> numericParams,
        Map<String, String> stringParams,
        Map<String, Boolean> booleanParams
) {
}
