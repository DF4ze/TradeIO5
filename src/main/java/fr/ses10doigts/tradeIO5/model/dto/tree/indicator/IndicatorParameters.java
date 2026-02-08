package fr.ses10doigts.tradeIO5.model.dto.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
public class IndicatorParameters {

    private final IndicatorType indicatorType;

    /**
     * Paramètres typés mais génériques
     * ex: period=14, fast=12, slow=26
     */
    private final Map<String, Double> numerics;

    private final Map<String, String> strings;

    private final Map<String, Boolean> booleans;

    private final ApiCredentialDTO credential;

    public Double getNumeric(String key ){
        return numerics.get(key);
    }
    public String getString(String key ){
        return strings.get(key);
    }
    public Boolean getBoolean( String key ){
        return booleans.get(key);
    }


}
