package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorExecutionKey;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IndicatorCache {

    private final Map<IndicatorExecutionKey, IndicatorSnapshot> cache =
            new ConcurrentHashMap<>();

    public boolean contains(IndicatorExecutionKey key) {
        clearIfOutdated(key);
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

    public void clear(IndicatorType indicatorCode) {
        cache.entrySet().removeIf(
                e -> e.getKey().indicatorType().equals(indicatorCode)
        );
    }

    public void clearOutdated() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(e -> isOutdated(e.getKey().context(), now));
    }

    private void clearIfOutdated(IndicatorExecutionKey key){
        if( cache.containsKey(key) ) {
            Instant now = Instant.now();
            if (isOutdated(key.context(), now)) {
                cache.remove(key);
            }
        }
    }

    private boolean isOutdated(IndicatorContext ctx, Instant now) {
        long validitySeconds = ctx.getTimeframe().getNbSeconds();
        return now.isAfter(ctx.getTimestamp().plusSeconds(validitySeconds));
    }
}
