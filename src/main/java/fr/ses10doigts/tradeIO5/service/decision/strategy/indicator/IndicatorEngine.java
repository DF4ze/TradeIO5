package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;


/**
 * Rôle de l’IndicatorEngine
 *
 * orchestrer l’exécution des indicateurs
 * gérer les dépendances (MACD → EMA, etc.)
 * injecter les paramètres
 * produire un snapshot explicable
 * éviter les recalculs inutiles
 */
@Service
@RequiredArgsConstructor
public class IndicatorEngine {
    private final Logger logger = LoggerFactory.getLogger(IndicatorEngine.class);

    private final IndicatorRegistry indicatorRegistry;
    private final IndicatorCache indicatorCache;

    public IndicatorSnapshot execute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        logger.debug("Nb Indicators : {}", indicatorRegistry.size());

        Indicator indicator = indicatorRegistry.get(parameters.getIndicatorType());

        IndicatorExecutionKey key =
                IndicatorExecutionKey.of(indicator, context, parameters);

        // 1. cache
        if (indicatorCache.contains(key)) {
            return indicatorCache.get(key);
        }

        // 2. résolution des dépendances
        IndicatorContext enrichedContext =
                resolveDependencies(indicator, context, parameters);

        // 3. calcul
        IndicatorValue value = indicator.compute(enrichedContext, parameters);

        // 4. snapshot
        IndicatorSnapshot snapshot = IndicatorSnapshot.builder()
                .indicatorCode(indicator.getType())
                .parameters(parameters)
                .context(enrichedContext)
                .value(value)
                .build();

        // 5. cache
        indicatorCache.put(key, snapshot);

        return snapshot;
    }

    private IndicatorContext resolveDependencies(
            Indicator indicator,
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        if (!(indicator instanceof DependentIndicator dependentIndicator)) {
            return context;
        }

        Map<IndicatorDependencyKey, IndicatorSnapshot> resolvedDependencies =
                new HashMap<>(context.getDependencies());

        for (IndicatorDependency dependency : dependentIndicator.getDependencies(parameters)) {

            IndicatorSnapshot snapshot = execute(
                    context,
                    dependency.parameters()
            );

            resolvedDependencies.put(
                    dependency.key(),
                    snapshot
            );
        }

        return IndicatorContext.builder()
                .symbol(context.getSymbol())
                .timeframe(context.getTimeframe())
                .marketData(context.getMarketData())
                .timestamp(context.getTimestamp())
                .dependencies(resolvedDependencies)
                .build();
    }


}
