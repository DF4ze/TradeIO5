package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RainbowSmaIndicatorTest {


    @Autowired
    private IndicatorEngine indicatorEngine;

    static IndicatorParameters rainParams;

    @BeforeAll
    static void init() {
        rainParams = rainbowParams(5, 3, 7, 14, -3, -7);
    }

    @Test
    void wrongParameters(){
        IndicatorParameters parameters = IndicatorParameters.builder().numerics(Map.of())
                .indicatorType(IndicatorType.RAINBOW)
                .build();
        IndicatorContext context = context(List.of(
                bd(10), bd(10), bd(10), bd(10), bd(10), bd(10), bd(10)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, parameters);
        assertFalse(snap.getResult().isValid());
    }

    @Test
    void compute() {
        IndicatorContext context = context(List.of(
                bd(10), bd(10), bd(10), bd(10), bd(10), bd(10), bd(10)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, rainParams);

        assertTrue(snap.getResult().isValid());
        assertNotNull(snap.getResult().getValue());
        assertNotNull(snap.getResult().getValues());
    }

    @Test
    void compute_withFewerDataThanPeriod() {
        IndicatorContext context = context(List.of(
                bd(10), bd(10)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, rainParams);
        assertFalse(snap.getResult().isValid());
    }

    @Test
    void compute_withEmptyData() {
        IndicatorContext context = context(List.of(
                bd(10), bd(10)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, rainParams);

        assertFalse(snap.getResult().isValid());
    }

    @Test
    void compute_withJustEnoughDataPoint() {
        IndicatorContext context = context(List.of(
                bd(10), bd(10), bd(10), bd(10), bd(10)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, rainParams);

        Double sma = snap.getResult().getValue();
        Map<String, Double> rain = snap.getResult().getValues();

        assertTrue(snap.getResult().isValid());
        assertEquals(10.0, sma);

        assertTrue(sma < rain.get(RainbowSmaIndicator.P_PERC_UP1));
        assertTrue(rain.get(RainbowSmaIndicator.P_PERC_UP1) < rain.get(RainbowSmaIndicator.P_PERC_UP2));
        assertTrue(rain.get(RainbowSmaIndicator.P_PERC_UP2) < rain.get(RainbowSmaIndicator.P_PERC_UP3));


        assertTrue(sma > rain.get(RainbowSmaIndicator.P_PERC_DOWN1));
        assertTrue(rain.get(RainbowSmaIndicator.P_PERC_DOWN1) > rain.get(RainbowSmaIndicator.P_PERC_DOWN2));
    }


    @Test
    void compute_withDecreasingData() {
        IndicatorContext context = context(List.of(
                bd(50), bd(40), bd(30), bd(20), bd(10)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, rainParams);

        assertTrue(snap.getResult().isValid());
        assertTrue(snap.getResult().getValue() > 10.0); // SMA > dernière valeur
    }

    @Test
    void compute_withIncreasingData() {
        IndicatorContext context = context(List.of(
                bd(10), bd(20), bd(30), bd(40), bd(50)
        ));

        IndicatorSnapshot snap = indicatorEngine.execute(context, rainParams);

        assertTrue(snap.getResult().isValid());
        assertTrue(snap.getResult().getValue() > 10.0); // SMA > dernière valeur
    }

    @Test
    void compute_stability() {
        List<BigDecimal> data = List.of(bd(10), bd(15), bd(20), bd(25), bd(30));
        IndicatorSnapshot sma1 = indicatorEngine.execute(context(data), rainParams);
        IndicatorSnapshot sma2 = indicatorEngine.execute(context(data), rainParams);

        assertEquals(sma1.getResult().getValue(), sma2.getResult().getValue());
    }

}