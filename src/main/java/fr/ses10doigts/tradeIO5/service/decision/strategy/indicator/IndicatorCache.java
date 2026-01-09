package fr.ses10doigts.tradeIO5.service.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorExecutionKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IndicatorCache {

    private final Map<IndicatorExecutionKey, IndicatorSnapshot> cache =
            new ConcurrentHashMap<>();

    public boolean contains(IndicatorExecutionKey key) {
        return cache.containsKey(key);
    }

    public IndicatorSnapshot get(IndicatorExecutionKey key) {
        return cache.get(key);
    }

    public void put(IndicatorExecutionKey key, IndicatorSnapshot snapshot) {
        cache.put(key, snapshot);
    }

    public void clear() {
        cache.clear();
    }

    public void clear(IndicatorCode indicatorCode) {
        cache.entrySet().removeIf(
                e -> e.getKey().indicatorCode().equals(indicatorCode)
        );
    }
}
