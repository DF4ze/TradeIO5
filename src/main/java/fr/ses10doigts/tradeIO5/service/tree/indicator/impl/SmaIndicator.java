package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class SmaIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(SmaIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    @Override
    public IndicatorType getType() {
        return IndicatorType.SMA;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        int nbRequied = 0;
        if( parameters.getNumeric(P_PERIOD_NAME) != null )
            nbRequied = parameters.getNumeric(P_PERIOD_NAME).intValue();
        return nbRequied;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(P_PERIOD_NAME);
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {

        Double period = parameters.getNumeric(P_PERIOD_NAME);
        if (period == null) {
            logger.error("Invalid parameter : {}", P_PERIOD_NAME);
            return IndicatorResult.invalid();
        }

        List<MarketData> data = context.getMarketDataset().getMarketDatas();

        if (data.size() < period) {
            logger.error("Invalid context : MarketData size too short");
            return IndicatorResult.invalid();
        }

        // calcul SMA
        BigDecimal sum = BigDecimal.ZERO;
        int startIndex = data.size() - period.intValue();
        for (int i = startIndex; i < data.size(); i++) {
            sum = sum.add( BigDecimal.valueOf( data.get(i).getClose().doubleValue() ));
        }

        BigDecimal sma = sum.divide( BigDecimal.valueOf( period ), RoundingMode.HALF_EVEN);

        return IndicatorResult.builder()
                .value(sma.doubleValue())
                .valid(true)
                .build();
    }

}
