package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorDependency;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;

import java.util.List;

public interface DependentIndicator {

    List<IndicatorDependency> getDependencies(IndicatorParameters parameters);

}
