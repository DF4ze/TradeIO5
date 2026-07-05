package fr.ses10doigts.tradeIO5.service.tree.indicator;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorExecutionKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@link IndicatorCache#contains}, which internally calls
 * isOutdated() -> now.isAfter(ctx.clock().now().plus(tf.getAmount(), tf.getUnit())).
 * <p>
 * Bug: for CALENDAR timeframes (M1, M2, M3, M6, W1, W2, Y1, Y3), tf.getUnit() is
 * ChronoUnit.MONTHS/WEEKS/YEARS. Instant.plus(long, TemporalUnit) only supports units
 * up to DAYS, so calling it directly with MONTHS/WEEKS/YEARS throws
 * UnsupportedTemporalTypeException ("Unsupported unit: Months") as soon as a cache
 * entry already present is re-checked (contains() -> clearIfOutdated() -> isOutdated()).
 * <p>
 * Fix: isOutdated() now delegates to TimeFrame#addTo(Instant), which goes through
 * ZonedDateTime (which does support calendar units) instead of calling Instant#plus directly.
 */
@DisplayName("IndicatorCache - calendar timeframe outdated check")
class IndicatorCacheTest {

    @ParameterizedTest(name = "contains() should not throw for calendar timeframe {0}")
    @EnumSource(value = TimeFrame.class, names = {"M1", "M2", "M3", "M6", "W1", "W2", "Y1", "Y3"})
    void should_not_throw_when_checking_cache_for_calendar_timeframes(TimeFrame calendarTimeFrame) {
        IndicatorCache cache = new IndicatorCache();

        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        FixedDomainClock clock = new FixedDomainClock(fixedNow);

        IndicatorContext context = new IndicatorContext(
                "BTCUSDT",
                calendarTimeFrame,
                MarketDataset.builder().timeFrame(calendarTimeFrame).build(),
                Map.of(),
                clock
        );

        IndicatorParameters parameters = IndicatorParameters.builder()
                .indicatorType(IndicatorType.RSI)
                .numerics(Map.of())
                .build();

        IndicatorExecutionKey key = new IndicatorExecutionKey(IndicatorType.RSI, parameters, context);

        IndicatorSnapshot snapshot = IndicatorSnapshot.builder()
                .indicatorType(IndicatorType.RSI)
                .parameters(parameters)
                .context(context)
                .result(IndicatorResult.builder().valid(true).value(50.0).build())
                .build();

        cache.put(key, snapshot);

        // First contains() call after put(): entry exists, triggers clearIfOutdated() ->
        // isOutdated(), which is exactly where the old code crashed on calendar units.
        assertDoesNotThrow(() -> {
            cache.contains(key, fixedNow);
        });
    }

    @Test
    @DisplayName("cache entry is still considered fresh right after being put, for a calendar timeframe")
    void should_keep_entry_fresh_immediately_after_put_for_calendar_timeframe() {
        IndicatorCache cache = new IndicatorCache();

        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        FixedDomainClock clock = new FixedDomainClock(fixedNow);

        IndicatorContext context = new IndicatorContext(
                "BTCUSDT",
                TimeFrame.M1,
                MarketDataset.builder().timeFrame(TimeFrame.M1).build(),
                Map.of(),
                clock
        );

        IndicatorParameters parameters = IndicatorParameters.builder()
                .indicatorType(IndicatorType.RSI)
                .numerics(Map.of())
                .build();

        IndicatorExecutionKey key = new IndicatorExecutionKey(IndicatorType.RSI, parameters, context);

        IndicatorSnapshot snapshot = IndicatorSnapshot.builder()
                .indicatorType(IndicatorType.RSI)
                .parameters(parameters)
                .context(context)
                .result(IndicatorResult.builder().valid(true).value(50.0).build())
                .build();

        cache.put(key, snapshot);

        assertTrue(cache.contains(key, fixedNow),
                "entry should still be considered fresh immediately (same instant, 1 month timeframe)");
        assertFalse(cache.contains(key, fixedNow.plus(Duration.ofDays(400))),
                "entry should be considered outdated well over a month (1 month timeframe) later");
    }
}
