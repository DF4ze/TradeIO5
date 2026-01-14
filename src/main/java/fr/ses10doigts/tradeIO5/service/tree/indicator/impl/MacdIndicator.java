package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.*;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.DependentIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MacdIndicator implements Indicator, DependentIndicator {
    private final Logger logger = LoggerFactory.getLogger(MacdIndicator.class);


    public static final String P_FAST_PERIOD_NAME = "fastPeriod";
    public static final String P_SLOW_PERIOD_NAME = "slowPeriod";


    private static final IndicatorDependencyKey K_FAST_EMA =
            new IndicatorDependencyKey(IndicatorType.EMA, "FAST");

    private static final IndicatorDependencyKey K_SLOW_EMA =
            new IndicatorDependencyKey(IndicatorType.EMA, "SLOW");

    @Override
    public IndicatorType getType() {
        return IndicatorType.MACD;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(P_FAST_PERIOD_NAME, P_SLOW_PERIOD_NAME);
    }

    @Override
    public List<IndicatorDependency> getDependencies(
            IndicatorParameters parameters
    ) {

        return List.of(
                new IndicatorDependency(
                        K_FAST_EMA,
                        new IndicatorParameters(
                                IndicatorType.EMA,
                                Map.of(EmaIndicator.P_PERIOD_NAME, parameters.getNumeric(P_FAST_PERIOD_NAME)),
                                Map.of(),
                                Map.of(),
                                null
                        )
                ),
                new IndicatorDependency(
                        K_SLOW_EMA,
                        new IndicatorParameters(
                                IndicatorType.EMA,
                                Map.of(EmaIndicator.P_PERIOD_NAME, parameters.getNumeric(P_SLOW_PERIOD_NAME)),
                                Map.of(),
                                Map.of(),
                                null
                        )
                )
        );
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {

        IndicatorSnapshot fast =
                context.getDependencies().get(K_FAST_EMA);

        IndicatorSnapshot slow =
                context.getDependencies().get(K_SLOW_EMA);

        if (fast == null || slow == null ||
                fast.getResult() == null || slow.getResult() == null ||
                !fast.getResult().isValid() ||  !slow.getResult().isValid()) {
            logger.error("Invalid dependency : FAST_EMA or SLOW_EMA");
            return IndicatorResult.invalid();
        }

        double macd =
                fast.getResult().getValue()
                        - slow.getResult().getValue();

        return IndicatorResult.builder()
                .value(macd)
                .valid(true)
                .build();
    }

}