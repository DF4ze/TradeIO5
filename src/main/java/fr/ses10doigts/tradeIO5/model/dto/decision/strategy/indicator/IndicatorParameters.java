package fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class IndicatorParameters {

    private final IndicatorCode indicatorCode;

    /**
     * Paramètres typés mais génériques
     * ex: period=14, fast=12, slow=26
     */
    private final Map<String, Double> numericParams;

    private final Map<String, String> stringParams;

    private final Map<String, Boolean> booleanParams;

    public Double getNumeric( String key ){
        return numericParams.get(key);
    }
    public String getString( String key ){
        return stringParams.get(key);
    }
    public Boolean getBoolean( String key ){
        return booleanParams.get(key);
    }


}
