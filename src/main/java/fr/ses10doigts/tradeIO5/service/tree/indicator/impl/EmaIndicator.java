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
public class EmaIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(EmaIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    @Override
    public IndicatorType getType() {
        return IndicatorType.EMA;
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
        List<MarketData> data = context.getMarketDataset().getMarketDatas();

        if (data.size() < period) {
            logger.error("Invalid context : MarketData size too short");
            return IndicatorResult.invalid();
        }

        // calcul EMA classique (pseudo simplifié)
        BigDecimal bdPeriod = BigDecimal.valueOf(period);
        BigDecimal multiplier = BigDecimal.valueOf(2)
                .divide(bdPeriod.add(BigDecimal.ONE), 10, RoundingMode.HALF_EVEN); // précision intermédiaire

        // Initialisation EMA en BigDecimal
        BigDecimal ema = data.get(0).getClose();

        // Calcul EMA
        for (int i = 1; i < data.size(); i++) {
            BigDecimal close = data.get(i).getClose();
            ema = (close.subtract(ema)).multiply(multiplier).add(ema);
        }


        return IndicatorResult.builder()
                .value(ema.doubleValue())
                .valid(true)
                .build();
    }

}
