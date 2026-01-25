package fr.ses10doigts.tradeIO5.model.dto.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;

import java.math.BigDecimal;
import java.util.Map;


public record MarketContext(
        String symbol,
        BigDecimal lastPrice,
        DomainClock clock,
        Map<TimeFrame, MarketDataset> series,
        Map<IndicatorKey, IndicatorResult> indicatorValues
) {
    public void addIndicatorValue(IndicatorKey key, IndicatorResult value) {
        indicatorValues.put(key, value);
    }
}
