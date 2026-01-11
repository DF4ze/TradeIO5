package fr.ses10doigts.tradeIO5.model.dto.decision.strategy;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorValue;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class MarketContext {

    private String symbol;
    private BigDecimal lastPrice;
    private Instant timestamp;

    private Map<TimeFrame, MarketDataSeries> series;

    @Builder.Default
    private Map<IndicatorKey, IndicatorValue> indicatorValues = new HashMap<>();

    public void addIndicatorValue(IndicatorKey key, IndicatorValue value ){
        indicatorValues.put(key, value);
    }
}
