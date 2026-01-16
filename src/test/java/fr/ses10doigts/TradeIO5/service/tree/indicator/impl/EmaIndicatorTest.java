package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.*;
import static org.junit.jupiter.api.Assertions.*;


class EmaIndicatorTest {
    private Indicator emaIndicator = new EmaIndicator();

    @BeforeAll
    static void Init() {
    }

    @Test
    void compute() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of(
                        bd(10), bd(10), bd(10), bd(10), bd(10), bd(10), bd(10)
                )),
                periodParams(5)
        );

        assertTrue(ema.isValid());
        assertNotNull(ema.getValue());
    }

    @Test
    void compute_withFewerDataThanPeriod() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of(bd(10), bd(20))),
                periodParams(5)
        );

        // Selon ton implémentation, EMA peut être invalide ou renvoyer la première valeur
        assertFalse(ema.isValid());
    }

    @Test
    void compute_withEmptyData() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of()),
                periodParams(5)
        );

        assertFalse(ema.isValid());
    }

    @Test
    void compute_withJustEnoughDataPoint() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of(bd(15), bd(15))),
                periodParams(2)
        );

        assertTrue(ema.isValid());
        assertEquals(15.0, ema.getValue());
    }

    @Test
    void compute_twoPoints_period2_exactResult() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of(bd(3), bd(6))),
                periodParams(2)
        );

        assertTrue(ema.isValid());
        assertEquals(5.0, Math.round(ema.getValue()));
    }

    @Test
    void compute_withDecreasingData() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of(
                        bd(50), bd(40), bd(30), bd(20), bd(10)
                )),
                periodParams(3)
        );

        assertTrue(ema.isValid());
        assertTrue(ema.getValue() > 10.0); // EMA > dernière valeur
    }

    @Test
    void compute_withIncreasingData() {
        IndicatorResult ema = emaIndicator.compute(
                context(List.of(
                        bd(10), bd(20), bd(30), bd(40), bd(50)
                )),
                periodParams(3)
        );

        assertTrue(ema.isValid());
        assertTrue(ema.getValue() < 50.0); // EMA < dernière valeur
    }

    @Test
    void compute_stability() {
        List<BigDecimal> data = List.of(bd(10), bd(15), bd(20), bd(25), bd(30));
        IndicatorResult ema1 = emaIndicator.compute(context(data), periodParams(3));
        IndicatorResult ema2 = emaIndicator.compute(context(data), periodParams(3));

        assertEquals(ema1.getValue(), ema2.getValue());
    }

}