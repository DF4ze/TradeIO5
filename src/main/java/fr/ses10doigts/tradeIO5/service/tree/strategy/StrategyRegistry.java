package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.StrategyType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {

    private final Map<String, Strategy> strategies;

    public StrategyRegistry(List<Strategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        s -> s.getClass().getSimpleName(),
                        Function.identity()
                ));
    }

    public List<Strategy> getAll() {
        return List.copyOf(strategies.values());
    }

    public List<Strategy> getByType(StrategyType strategyName) {
        return strategies.values().stream()
                .filter(s -> s.getType().contains(strategyName))
                .toList();
    }

    public Strategy get(String name) {
        if( !strategies.containsKey(name) )
            throw new IllegalArgumentException("Unknown strategy : "+name);

        return strategies.get(name);
    }
}
