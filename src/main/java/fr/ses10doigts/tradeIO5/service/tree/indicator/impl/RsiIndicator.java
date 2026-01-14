package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

@Component
public class RsiIndicator implements Indicator {
    private final Logger logger = LoggerFactory.getLogger(RsiIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    @Override
    public IndicatorType getType() {
        return IndicatorType.RSI;
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
        double period = parameters.getNumeric(P_PERIOD_NAME);

        MarketDataset series = context.getMarketDataset();
        List<MarketData> data = series.getMarketDatas();

        if (data.size() < period + 1) {
            return IndicatorResult.invalid();
        }

        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;

        for (int i = data.size() - (int)period ; i < data.size(); i++) {
            BigDecimal diff = data.get(i).getClose()
                    .subtract(data.get(i - 1).getClose());

            if (diff.signum() > 0) {
                gain = gain.add(diff);
            } else {
                loss = loss.add(diff.abs());
            }
        }

        BigDecimal avgGain = gain.divide(BigDecimal.valueOf(period), MathContext.DECIMAL64);
        BigDecimal avgLoss = loss.divide(BigDecimal.valueOf(period), MathContext.DECIMAL64);

        BigDecimal rsi;

        if (avgGain.compareTo(BigDecimal.ZERO) == 0 && avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            // marché flat
            rsi = BigDecimal.valueOf(50);
        } else if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            // uniquement des gains
            rsi = BigDecimal.valueOf(100);
        } else if (avgGain.compareTo(BigDecimal.ZERO) == 0) {
            // uniquement des pertes
            rsi = BigDecimal.valueOf(0);
        } else {
            BigDecimal rs = avgGain.divide(avgLoss, MathContext.DECIMAL64);
            rsi = BigDecimal.valueOf(100)
                    .subtract(
                            BigDecimal.valueOf(100)
                                    .divide(BigDecimal.ONE.add(rs), MathContext.DECIMAL64)
                    );
        }

        logger.debug("{} indicator with {} = {} on TF {} returns {}", getType(), P_PERIOD_NAME, period, series.getTimeFrame(), rsi);

        return IndicatorResult.builder()
                .value(rsi.doubleValue())
                .valid(true)
                .build();
    }

}
