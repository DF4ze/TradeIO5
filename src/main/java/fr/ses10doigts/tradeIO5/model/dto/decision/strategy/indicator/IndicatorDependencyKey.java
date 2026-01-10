package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;

public record IndicatorDependencyKey(
        IndicatorType indicatorCode,
        String role
) {}