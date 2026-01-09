package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorValue;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmaIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(EmaIndicator.class);

    @Override
    public IndicatorCode getCode() {
        return IndicatorCode.EMA;
    }

    @Override
    public IndicatorValue compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {

        Double period = parameters.getNumeric("period");
        if( period == null ) {
            logger.error("Invalid parameter : period");
            return IndicatorValue.invalid();
        }

        List<MarketData> data = context.getMarketData().getMarketDatas();

        if (data.size() < period) {
            logger.error("Invalid context : MarketData size too short");
            return IndicatorValue.invalid();
        }

        // calcul EMA classique (pseudo simplifié)
        double multiplier = 2.0 / (period + 1);
        double ema = data.get(0).getClose().doubleValue();

        for (int i = 1; i < data.size(); i++) {
            double close = data.get(i).getClose().doubleValue();
            ema = (close - ema) * multiplier + ema;
        }

        return IndicatorValue.builder()
                .value(ema)
                .valid(true)
                .build();
    }
}
