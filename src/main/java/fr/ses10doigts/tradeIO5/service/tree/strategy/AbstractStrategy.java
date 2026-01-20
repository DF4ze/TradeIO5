package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public abstract class AbstractStrategy implements Strategy{

    final IndicatorRegistry indicatorRegistry;

    @Override
    public Map<TimeFrame, Integer> getRequiredCandles(StrategyParameters parameters) {

        Map<TimeFrame, Integer> nbRequired = new HashMap<>();

        for(Map.Entry<IndicatorKey, IndicatorParameters> set : parameters.getIndicatorParameters().entrySet()){

            Indicator indicator = indicatorRegistry.get(set.getValue().getIndicatorType());
            TimeFrame timeFrame = set.getKey().getTimeFrame();
            int requiredData = indicator.getRequiredData(set.getValue());

            if(nbRequired.containsKey( timeFrame) && requiredData > nbRequired.get(timeFrame)) {
                if (requiredData > nbRequired.get(timeFrame)) {
                    nbRequired.put( timeFrame, requiredData );
                }
            }else{
                nbRequired.put(timeFrame, requiredData);
            }
        }

        return nbRequired;
    }

}
