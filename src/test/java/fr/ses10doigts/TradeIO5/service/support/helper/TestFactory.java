package fr.ses10doigts.tradeIO5.service.support.helper;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl.MacdIndicator;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl.RainbowSmaIndicator;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl.RsiIndicator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class TestFactory {

    public static IndicatorContext context(List<BigDecimal> closes) {
        List<MarketData> data = closes.stream()
                .map(c -> MarketData.builder().close(c).build())
                .toList();

        MarketDataset series = MarketDataset.builder()
                .marketDatas(data)
                .timeFrame(TimeFrame.M1)
                .build();

        return IndicatorContext.builder()
                .marketDataset(series)
                .build();
    }

    public static IndicatorParameters periodParams(int period) {
        return IndicatorParameters.builder()
                .numerics(Map.of(RsiIndicator.P_PERIOD_NAME, (double) period))
                .build();
    }

    public static IndicatorParameters macdParams(int fastPeriod, int slowPeriod) {
        return IndicatorParameters.builder()
                .numerics(Map.of(
                        MacdIndicator.P_FAST_PERIOD_NAME, (double) fastPeriod,
                        MacdIndicator.P_SLOW_PERIOD_NAME, (double) slowPeriod)
                )
                .indicatorType(IndicatorType.MACD)
                .build();
    }

    public static IndicatorParameters rainbowParams(int period, double pu1, double pu2, double pu3, double pd1, double pd2) {
        return IndicatorParameters.builder()
                .numerics(Map.of(
                        RainbowSmaIndicator.P_PERIOD_NAME, (double) period,
                        RainbowSmaIndicator.P_PERC_UP1, pu1,
                        RainbowSmaIndicator.P_PERC_UP2, pu2,
                        RainbowSmaIndicator.P_PERC_UP3, pu3,
                        RainbowSmaIndicator.P_PERC_DOWN1, pd1,
                        RainbowSmaIndicator.P_PERC_DOWN2, pd2
                        )
                )
                .indicatorType(IndicatorType.RAINBOW)
                .build();
    }

    public static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
