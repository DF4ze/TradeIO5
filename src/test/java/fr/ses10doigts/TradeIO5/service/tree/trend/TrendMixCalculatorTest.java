package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrendMixCalculatorTest {

    private static List<MarketData> candles(double... closes) {
        List<MarketData> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            BigDecimal c = BigDecimal.valueOf(closes[i]);
            out.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochSecond(86400L * i))
                    .pair("TESTUSDT").open(c).high(c.multiply(BigDecimal.valueOf(1.01)))
                    .low(c.multiply(BigDecimal.valueOf(0.99))).close(c).volume(BigDecimal.ZERO).build());
        }
        return out;
    }

    private static double[] sma(List<MarketData> c, int p) {
        double[] out = new double[c.size()];
        for (int i = 0; i < out.length; i++) {
            if (i < p - 1) { out[i] = Double.NaN; continue; }
            double s = 0;
            for (int k = i - p + 1; k <= i; k++) { s += c.get(k).getClose().doubleValue(); }
            out[i] = s / p;
        }
        return out;
    }

    /** ATR constant (2 % du prix de départ) : suffisant pour tester la composition, pas le calcul d'ATR. */
    private static double[] atr(List<MarketData> c, int p, double v) {
        double[] out = new double[c.size()];
        Arrays.fill(out, 0, p - 1, Double.NaN);
        Arrays.fill(out, p - 1, out.length, v);
        return out;
    }

    private static double[] series(int up, int down) {
        double[] c = new double[up + down + 120];
        double v = 100;
        for (int i = 0; i < c.length; i++) {
            if (i >= 120 && i < 120 + up) { v *= 1.01; }
            else if (i >= 120 + up) { v *= 0.99; }
            c[i] = v;
        }
        return c;
    }

    @Test
    void undefinedBeforeWarmup() {
        List<MarketData> c = candles(series(60, 60));
        TrendMixCalculator.Params p = TrendMixCalculator.Params.defaults();
        TrendMixCalculator.Result r = TrendMixCalculator.compute(c, sma(c, 100), atr(c, 5, 1.0), p);
        assertNull(r.regime()[p.warmup() - 2]);
        assertTrue(r.regime()[p.warmup() - 1] != null);
    }

    @Test
    void followsUpThenDown() {
        List<MarketData> c = candles(series(80, 80));
        TrendMixCalculator.Params p = TrendMixCalculator.Params.defaults();
        TrendMixCalculator.Result r = TrendMixCalculator.compute(c, sma(c, 100), atr(c, 5, 1.0), p);
        assertEquals(TrendRegime.UP, r.regime()[120 + 79]);
        assertEquals(TrendRegime.DOWN, r.regime()[c.size() - 1]);
    }

    @Test
    void smaDecidesWhileRegressionIsInRange() {
        // série plate : régression RANGE ; chute brutale finale -> la SMA (mèche basse) passe DOWN avant toute régression
        double[] flat = new double[200];
        Arrays.fill(flat, 100.0);
        flat[199] = 80.0;
        List<MarketData> c = candles(flat);
        TrendMixCalculator.Params p = new TrendMixCalculator.Params(14, 30, 60, 400, 0.30, 0.10, 10, 100, 5, 1.25, true, false);
        TrendMixCalculator.Result r = TrendMixCalculator.compute(c, sma(c, 100), atr(c, 5, 2.0), p);
        assertEquals(TrendRegime.RANGE, r.regression()[198]);
        assertEquals(TrendRegime.DOWN, r.sma()[199]);
        assertEquals(TrendRegime.DOWN, r.regime()[199]);
        assertEquals(TrendMixCalculator.Source.SMA, r.source()[199]);
    }

    @Test
    void rejectsMisalignedInputs() {
        List<MarketData> c = candles(series(10, 10));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TrendMixCalculator.compute(c, new double[3], new double[3], TrendMixCalculator.Params.defaults()));
    }
}
