package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.entity.decision.strategy.indicator.IndicatorParameter;
import fr.ses10doigts.tradeIO5.model.entity.decision.strategy.indicator.IndicatorParameterSet;
import fr.ses10doigts.tradeIO5.repository.decision.IndicatorParameterSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class IndicatorParameterService {

    private final IndicatorParameterSetRepository setRepository;

    /**
     * Charge un set activé et le transforme en DTO pur pour le moteur
     */
    public IndicatorParameters loadParameters(Long parameterSetId) {
        IndicatorParameterSet set = setRepository.findById(parameterSetId)
            .orElseThrow(() -> new IllegalArgumentException("Parameter set introuvable"));

        Map<String, Double> numeric = new HashMap<>();
        Map<String, String> string = new HashMap<>();
        Map<String, Boolean> bool = new HashMap<>();

        for (IndicatorParameter param : set.getParameters()) {
            switch (param.getType()) {
                case NUMERIC -> numeric.put(param.getKey(), param.getNumericValue());
                case STRING -> string.put(param.getKey(), param.getStringValue());
                case BOOLEAN -> bool.put(param.getKey(), param.getBooleanValue());
            }
        }

        return new IndicatorParameters(
            set.getIndicatorCode(),
            numeric,
            string,
            bool,
                null // TODO
        );
    }
}
