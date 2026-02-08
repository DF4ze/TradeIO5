package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IndicatorRegistry {

    private final Map<IndicatorType, Indicator> indicators;

    /* Fills the indicator map */
    @Autowired
    public IndicatorRegistry(List<Indicator> indicatorList) {
        this.indicators = indicatorList.stream()
                .collect(Collectors.toMap(Indicator::getType, Function.identity()));
    }

    public Indicator get(IndicatorType code) {
        Indicator indicator = indicators.get(code);
        if (indicator == null) {
            throw new IllegalArgumentException("Indicator inconnu : " + code);
        }
        return indicator;
    }

    public boolean contains( IndicatorType code ){
        return indicators.containsKey(code);
    }

    public int size(){
        return indicators.size();
    }
}