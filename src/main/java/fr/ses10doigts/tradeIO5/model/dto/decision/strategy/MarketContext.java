package fr.ses10doigts.tradeIO5.model.dto.decision.strategy;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorValue;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class MarketContext {

    private String symbol;

    private BigDecimal lastPrice;
    private Instant timestamp;

    // Indicators
    private Map<IndicatorCode, IndicatorValue> indicators;

    // Position / portefeuille
    private BigDecimal positionSize;
    private BigDecimal avgEntryPrice;

    // Risk / state
    private boolean inPosition;
    private BigDecimal unrealizedPnL;

    // Market meta
    private TimeFrame timeFrame;
}
