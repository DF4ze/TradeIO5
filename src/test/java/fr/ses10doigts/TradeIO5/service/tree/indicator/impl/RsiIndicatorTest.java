    package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator - RSI")
class RsiIndicatorTest {
    private final RsiIndicator indicator = new RsiIndicator();

    private static DomainClock clock;

    @BeforeAll
    static void init(){
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Test
    void uptrend_should_return_high_rsi() {
        IndicatorResult rsi = indicator.compute(
                context(List.of(
                        bd(10), bd(11), bd(12), bd(13), bd(14), bd(15), bd(16)
                ), clock),
                periodParams(5)
        );

        assertTrue(rsi.isValid());
        assertTrue(rsi.getValue() > 60, "RSI should be high on uptrend");
    }

    @Test
    void downtrend_should_return_low_rsi() {
        IndicatorResult rsi = indicator.compute(
                context(List.of(
                        bd(16), bd(15), bd(14), bd(13), bd(12), bd(11), bd(10)
                ), clock),
                periodParams(5)
        );

        assertTrue(rsi.isValid());
        assertTrue(rsi.getValue() < 40, "RSI should be low on downtrend");
    }

    @Test
    void flat_should_return_middle_rsi() {
        IndicatorResult rsi = indicator.compute(
                context(List.of(
                        bd(10), bd(10), bd(10), bd(10), bd(10), bd(10), bd(10)
                ), clock),
                periodParams(5)
        );

        assertTrue(rsi.isValid());
        assertTrue(rsi.getValue() > 45 && rsi.getValue() < 55,
                "RSI should be around 50 on flat market");
    }

    @Test
    void movingMiddle_should_return_middle_rsi() {
        IndicatorResult rsi = indicator.compute(
                context(List.of(
                        bd(101), bd(100), bd(101), bd(100), bd(101), bd(100), bd(100.4)
                ), clock),
                periodParams(5)
        );

        assertTrue(rsi.isValid());
        assertTrue(rsi.getValue() > 45 && rsi.getValue() < 55,
                "RSI should be around 50 on flat market");
    }

}