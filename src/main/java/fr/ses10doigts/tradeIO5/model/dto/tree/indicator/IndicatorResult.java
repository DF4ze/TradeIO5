package fr.ses10doigts.tradeIO5.model.dto.tree.indicator;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Builder
@Data
public class IndicatorResult {

    private final Double value;

    // bornes théoriques (optionnelles mais utiles)
    private final Double min;
    private final Double max;

    private final Map<String, Double> values;

    // indicateur valide ou non (données insuffisantes, warmup, etc.)
    private final boolean valid;

    public static IndicatorResult invalid() {
        return IndicatorResult.builder()
                .valid(false)
                .build();
    }
}
