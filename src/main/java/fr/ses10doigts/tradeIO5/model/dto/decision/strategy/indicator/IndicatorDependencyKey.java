package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;

public record IndicatorDependencyKey(
        IndicatorCode indicatorCode,
        String role
) {}