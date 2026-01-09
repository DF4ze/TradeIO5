package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.*;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.DependentIndicator;
import fr.ses10doigts.tradeIO5.service.decision.strategy.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MacdIndicator implements Indicator, DependentIndicator {
    private final Logger logger = LoggerFactory.getLogger(MacdIndicator.class);

    private static final IndicatorDependencyKey FAST_EMA =
            new IndicatorDependencyKey(IndicatorCode.EMA, "FAST");

    private static final IndicatorDependencyKey SLOW_EMA =
            new IndicatorDependencyKey(IndicatorCode.EMA, "SLOW");

    @Override
    public IndicatorCode getCode() {
        return IndicatorCode.MACD;
    }

    @Override
    public List<IndicatorDependency> getDependencies(
            IndicatorParameters parameters
    ) {

        return List.of(
                new IndicatorDependency(
                        FAST_EMA,
                        new IndicatorParameters(
                                IndicatorCode.EMA,
                                Map.of("period", parameters.getNumeric("fastPeriod")),
                                Map.of(),
                                Map.of()
                        )
                ),
                new IndicatorDependency(
                        SLOW_EMA,
                        new IndicatorParameters(
                                IndicatorCode.EMA,
                                Map.of("period", parameters.getNumeric("slowPeriod")),
                                Map.of(),
                                Map.of()
                        )
                )
        );
    }

    @Override
    public IndicatorValue compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {

        IndicatorSnapshot fast =
                context.getDependencies().get(FAST_EMA);

        IndicatorSnapshot slow =
                context.getDependencies().get(SLOW_EMA);

        if (fast == null || slow == null) {
            logger.error("Invalid dependency : ");
            return IndicatorValue.invalid();
        }

        double macd =
                fast.getValue().getValue()
                        - slow.getValue().getValue();

        return IndicatorValue.builder()
                .value(macd)
                .valid(true)
                .build();
    }
}