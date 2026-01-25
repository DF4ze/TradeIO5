package fr.ses10doigts.tradeIO5.model.dto.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;

import java.util.Map;

/**
 * @param symbol        Actif / marché concerné (BTC/USDT, ETH/EUR, etc.)
 * @param timeframe     Timeframe du calcul (1m, 5m, 1h, 1d…)
 * @param marketDataset Séries de données marché (au minimum clôtures)
 * @param dependencies  Résultats d’indicateurs déjà calculés
 *                      (utile pour MACD, bandes, etc.)
 * @param clock     Moment logique du calcul (audit / replay)
 */

public record IndicatorContext(
        String symbol,
        TimeFrame timeframe,
        MarketDataset marketDataset,
        Map<IndicatorDependencyKey, IndicatorSnapshot> dependencies,
        DomainClock clock
) { }