package fr.ses10doigts.tradeIO5.service.calibration;

import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste {@link RegimeCalculator} (portage Java de
 * {@code tools/calibration/export_zones_v2.py::compute_adx}/{@code regime_segments}) sur des
 * bougies synthetiques : une longue tendance nette doit produire un segment "trend" (ADX>=25), un
 * marche choppy sans direction doit produire un segment "range" (ADX<20).
 */
class RegimeCalculatorTest {

    private static DailyCandle candle(int dayOffset, double open, double high, double low, double close) {
        return new DailyCandle(LocalDate.of(2024, 1, 1).plusDays(dayOffset), open, high, low, close, 10);
    }

    @Test
    @DisplayName("une tendance nette et soutenue produit un segment 'trend'")
    void computeAdx_detectsTrend() {
        List<DailyCandle> candles = new ArrayList<>();
        double price = 100;
        for (int i = 0; i < 60; i++) {
            double open = price;
            price += 2.0; // hausse reguliere et soutenue
            candles.add(candle(i, open, price + 0.2, open - 0.2, price));
        }
        double[] adx = RegimeCalculator.computeAdx(candles, RegimeCalculator.ADX_PERIOD);
        List<RegimeCalculator.RegimeSegment> segments = RegimeCalculator.regimeSegments(candles, adx);

        assertFalse(segments.isEmpty());
        assertTrue(segments.stream().anyMatch(s -> s.regime() == RegimeCalculator.Regime.TREND),
                "une tendance monotone soutenue devrait produire au moins un segment TREND");
    }

    @Test
    @DisplayName("un marche choppy sans direction produit un segment 'range'")
    void computeAdx_detectsRange() {
        List<DailyCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            double c = 100 + (i % 2 == 0 ? 0.4 : -0.4); // oscillation sans direction nette
            candles.add(candle(i, c, c + 0.5, c - 0.5, c));
        }
        double[] adx = RegimeCalculator.computeAdx(candles, RegimeCalculator.ADX_PERIOD);
        List<RegimeCalculator.RegimeSegment> segments = RegimeCalculator.regimeSegments(candles, adx);

        assertFalse(segments.isEmpty());
        assertTrue(segments.stream().anyMatch(s -> s.regime() == RegimeCalculator.Regime.RANGE),
                "un marche sans direction nette devrait produire au moins un segment RANGE");
    }
}
