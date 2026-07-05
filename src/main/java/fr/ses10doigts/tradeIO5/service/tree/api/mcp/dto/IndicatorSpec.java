package fr.ses10doigts.tradeIO5.service.tree.api.mcp.dto;

import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;

import java.util.Map;

/**
 * Description "plate" et LLM-friendly d'un indicateur à utiliser dans une stratégie, telle
 * qu'un appelant MCP peut la fournir en JSON (pas d'objet Java, uniquement des enums en
 * String et une map de nombres).
 *
 * @param indicatorType type de l'indicateur (ex: RSI)
 * @param timeFrame     timeframe sur lequel l'indicateur doit être calculé (ex: H1)
 * @param numericParams paramètres numériques de l'indicateur (ex: period, rsiBuyThreshold, rsiSellThreshold)
 */
public record IndicatorSpec(
        IndicatorType indicatorType,
        TimeFrame timeFrame,
        Map<String, Double> numericParams
) {
}
