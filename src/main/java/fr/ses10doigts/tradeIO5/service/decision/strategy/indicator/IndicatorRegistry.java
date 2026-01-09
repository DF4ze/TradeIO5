package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class IndicatorRegistry {

    private final Map<IndicatorCode, Indicator> indicators;

    /* Fills the indicator map */
    @Autowired
    public IndicatorRegistry(List<Indicator> indicatorList) {
        this.indicators = indicatorList.stream()
                .collect(Collectors.toMap(Indicator::getCode, Function.identity()));
    }

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

    public int size(){
        return indicators.size();
    }
}