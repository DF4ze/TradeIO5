package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.adxParams;
import static fr.ses10doigts.tradeIO5.service.support.helper.TestFactory.contextOhlc;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator - ADX")
class AdxIndicatorTest {

    private final AdxIndicator indicator = new AdxIndicator();

    private static DomainClock clock;

    @BeforeAll
    static void init() {
        Instant fixedNow = Instant.parse("2025-01-01T12:00:00Z");
        clock = new FixedDomainClock(fixedNow);
    }

    @Test
    @DisplayName("Tendance haussière forte et soutenue : ADX doit monter significativement, +DI > -DI")
    void strongUptrend_should_return_high_adx_with_plusDi_dominant() {
        int period = 7;

        // Bougies chaînées : high/low progressent régulièrement vers le haut,
        // avec un range constant -> +DM dominant, -DM nul, TR constant.
        List<double[]> ohlc = new ArrayList<>();
        double close = 100;
        for (int i = 0; i < 40; i++) {
            close += 2;
            ohlc.add(new double[]{close + 1, close - 1, close});
        }

        IndicatorContext context = contextOhlc(ohlc, clock);
        IndicatorResult adx = indicator.compute(context, adxParams(period));

        assertTrue(adx.isValid(), "ADX should be valid with enough data");
        assertTrue(adx.getValue() > 40, "ADX should rise significantly on a strong sustained uptrend, got " + adx.getValue());
        assertTrue(adx.getValues().get(AdxIndicator.V_PLUS_DI) > adx.getValues().get(AdxIndicator.V_MINUS_DI),
                "+DI should dominate -DI on a strong uptrend");
    }

    @Test
    @DisplayName("Marché en range/choppy : ADX doit rester bas")
    void choppyMarket_should_return_low_adx() {
        int period = 7;

        // Prix qui oscille entre deux bornes fixes à chaque bougie : les mouvements
        // haussiers et baissiers s'annulent, +DM_lissé ~= -DM_lissé -> DX ~= 0.
        List<double[]> ohlc = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double close = (i % 2 == 0) ? 105 : 95;
            ohlc.add(new double[]{close + 2, close - 2, close});
        }

        IndicatorContext context = contextOhlc(ohlc, clock);
        IndicatorResult adx = indicator.compute(context, adxParams(period));

        assertTrue(adx.isValid(), "ADX should be valid with enough data");
        assertTrue(adx.getValue() < 20, "ADX should stay low on a choppy/range-bound market, got " + adx.getValue());
    }

    @Test
    @DisplayName("Données insuffisantes (< 2 x period) : résultat invalide")
    void insufficientData_should_return_invalid() {
        int period = 7;

        List<double[]> ohlc = new ArrayList<>();
        double close = 100;
        for (int i = 0; i < 10; i++) { // 10 < 2*7 = 14 requis
            close += 1;
            ohlc.add(new double[]{close + 1, close - 1, close});
        }

        IndicatorContext context = contextOhlc(ohlc, clock);
        IndicatorResult adx = indicator.compute(context, adxParams(period));

        assertFalse(adx.isValid());
    }
}
