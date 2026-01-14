package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsiIndicatorTest {
    private final RsiIndicator indicator = new RsiIndicator();


    @BeforeAll
    static void init(){

    }

    @Test
    void uptrend_should_return_high_rsi() {
        IndicatorResult rsi = indicator.compute(
                context(List.of(
                        bd(10), bd(11), bd(12), bd(13), bd(14), bd(15), bd(16)
                )),
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
                )),
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
                )),
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
                )),
                periodParams(5)
        );

        assertTrue(rsi.isValid());
        assertTrue(rsi.getValue() > 45 && rsi.getValue() < 55,
                "RSI should be around 50 on flat market");
    }

}