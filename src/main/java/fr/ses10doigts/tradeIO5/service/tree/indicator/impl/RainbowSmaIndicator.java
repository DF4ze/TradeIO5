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
public class RainbowSmaIndicator implements Indicator, DependentIndicator {
    private final Logger logger = LoggerFactory.getLogger(RainbowSmaIndicator.class);

    public static final String P_PERIOD_NAME = "period";

    public static final String P_PERC_UP1 = "percup1";
    public static final String P_PERC_UP2 = "percup2";
    public static final String P_PERC_UP3 = "percup3";
    public static final String P_PERC_DOWN1 = "percdown1";
    public static final String P_PERC_DOWN2 = "percdown2";

    private static final IndicatorDependencyKey K_SMA =
            new IndicatorDependencyKey(IndicatorType.SMA, "BASE");


    @Override
    public IndicatorType getType() {
        return IndicatorType.RAINBOW;
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
        return List.of(P_PERIOD_NAME, P_PERC_UP1, P_PERC_UP2, P_PERC_UP3, P_PERC_DOWN1, P_PERC_DOWN2);
    }

    @Override
    public List<IndicatorDependency> getDependencies(
            IndicatorParameters parameters
    ) {

        return List.of(
                new IndicatorDependency(
                        K_SMA,
                        new IndicatorParameters(
                                IndicatorType.SMA,
                                Map.of(SmaIndicator.P_PERIOD_NAME, parameters.getNumeric(P_PERIOD_NAME)),
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

        IndicatorSnapshot smaIndic =
                context.getDependencies().get(K_SMA);


        if (smaIndic == null  || smaIndic.getResult() == null || !smaIndic.getResult().isValid() ) {
            logger.error("Invalid dependency : SMA");
            return IndicatorResult.invalid();
        }

        double sma = smaIndic.getResult().getValue();

        Map<String, Double> results = Map.of(
                P_PERC_UP1, percentDecay(sma, parameters.getNumeric( P_PERC_UP1)),
                P_PERC_UP2, percentDecay(sma, parameters.getNumeric( P_PERC_UP2)),
                P_PERC_UP3, percentDecay(sma, parameters.getNumeric( P_PERC_UP3)),
                P_PERC_DOWN1, percentDecay(sma, parameters.getNumeric( P_PERC_DOWN1)),
                P_PERC_DOWN2, percentDecay(sma, parameters.getNumeric( P_PERC_DOWN2))
        );



        return IndicatorResult.builder()
                .value(sma)
                .values(results)
                .valid(true)
                .build();
    }

    private double percentDecay( double base, double percent ){
        return base * (1 + (0.01*percent));
    }
}