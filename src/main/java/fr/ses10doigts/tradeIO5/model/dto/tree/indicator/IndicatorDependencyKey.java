package fr.ses10doigts.tradeIO5.model.dto.tree.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;

public record IndicatorDependencyKey(
        IndicatorType indicatorCode,
        String role
) {}