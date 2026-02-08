package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorExecutionKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IndicatorCache {

    private final Map<IndicatorExecutionKey, IndicatorSnapshot> cache =
            new ConcurrentHashMap<>();

    public boolean contains(IndicatorExecutionKey key, Instant presumeNow) {
        clearIfOutdated(key, presumeNow);
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

    public void clearOutdated(Instant presumeNow) {
        cache.entrySet().removeIf(e -> isOutdated(e.getKey().context(), presumeNow));
    }

    private void clearIfOutdated(IndicatorExecutionKey key, Instant presumeNow){
        if( cache.containsKey(key) ) {
            if (isOutdated(key.context(), presumeNow)) {
                cache.remove(key);
            }
        }
    }

    private boolean isOutdated(IndicatorContext ctx, Instant now) {
        TimeFrame tf = ctx.timeframe();
        return now.isAfter(ctx.clock().now().plus(tf.getAmount(), tf.getUnit()));
    }
}
