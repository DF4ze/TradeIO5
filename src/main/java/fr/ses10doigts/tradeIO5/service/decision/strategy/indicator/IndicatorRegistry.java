package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class IndicatorRegistry {

    private final Map<IndicatorCode, Indicator> indicators;

    public Indicator get(IndicatorCode code) {
        Indicator indicator = indicators.get(code);
        if (indicator == null) {
            throw new IllegalArgumentException("Indicator inconnu : " + code);
        }
        return indicator;
    }

    public boolean contains( IndicatorCode code ){
        return indicators.containsKey(code);
    }
}