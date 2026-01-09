package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class IndicatorValue {

    private final Double value;

    // bornes théoriques (optionnelles mais utiles)
    private final Double min;
    private final Double max;

    // indicateur valide ou non (données insuffisantes, warmup, etc.)
    private final boolean valid;

    // information humaine
    private final String unit;

    public static IndicatorValue invalid() {
        return IndicatorValue.builder()
                .valid(false)
                .build();
    }
}
