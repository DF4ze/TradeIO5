package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Indicator - LinearRegression")
class LinearRegressionIndicatorTest {

    private final Indicator linearRegressionIndicator = new LinearRegressionIndicator();

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Test
    void compute_withIncreasingData() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(
                        bd(10), bd(12), bd(14), bd(16), bd(18)
                ), clock),
                periodParams(5)
        );

        assertTrue(result.isValid());
        assertNotNull(result.getValue());
        assertTrue(result.getValue() > 0, "expected positive normalized slope, got " + result.getValue());
        assertEquals(1.0, result.getValues().get(LinearRegressionIndicator.V_R2), 1e-9);
    }

    @Test
    void compute_withDecreasingData() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(
                        bd(20), bd(15), bd(10), bd(5), bd(0)
                ), clock),
                periodParams(5)
        );

        assertTrue(result.isValid());
        assertTrue(result.getValue() < 0, "expected negative normalized slope, got " + result.getValue());
    }

    @Test
    void compute_withFlatData() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(
                        bd(10), bd(10), bd(10), bd(10), bd(10)
                ), clock),
                periodParams(5)
        );

        assertTrue(result.isValid());
        assertEquals(0.0, result.getValue());
        assertEquals(0.0, result.getValues().get(LinearRegressionIndicator.V_R2));
    }

    @Test
    void compute_withFewerDataThanPeriod() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(bd(10), bd(20)), clock),
                periodParams(5)
        );

        assertFalse(result.isValid());
    }

    @Test
    void compute_withEmptyData() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(), clock),
                periodParams(5)
        );

        assertFalse(result.isValid());
    }

    @Test
    void compute_withMissingPeriodParameter() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(bd(10), bd(20), bd(30)), clock),
                IndicatorParameters.builder().numerics(Map.of()).build()
        );

        assertFalse(result.isValid());
    }

    @Test
    void compute_stability() {
        var data = List.of(bd(10), bd(15), bd(20), bd(25), bd(30));
        IndicatorResult r1 = linearRegressionIndicator.compute(context(data, clock), periodParams(3));
        IndicatorResult r2 = linearRegressionIndicator.compute(context(data, clock), periodParams(3));

        assertEquals(r1.getValue(), r2.getValue());
    }

    @Test
    void compute_exposesSlopeAndRegressionValueInValuesMap() {
        IndicatorResult result = linearRegressionIndicator.compute(
                context(List.of(bd(10), bd(12), bd(14), bd(16), bd(18)), clock),
                periodParams(5)
        );

        assertTrue(result.isValid());
        assertEquals(2.0, result.getValues().get(LinearRegressionIndicator.V_SLOPE), 1e-9);
        assertEquals(18.0, result.getValues().get(LinearRegressionIndicator.V_REGRESSION_VALUE), 1e-9);
    }
}
