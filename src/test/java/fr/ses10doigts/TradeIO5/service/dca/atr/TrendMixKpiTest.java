package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.atr.TrendMixKpi.Front;
import fr.ses10doigts.tradeIO5.service.dca.atr.TrendMixKpi.Kpis;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KPI sur séries synthétiques à résultat connu. */
class TrendMixKpiTest {

    private static final double EPS = 1e-9;

    private static TrendRegime[] regimes(int n, Object... runs) {
        TrendRegime[] r = new TrendRegime[n];
        for (int k = 0; k < runs.length; k += 2) {
            int start = (Integer) runs[k];
            TrendRegime v = (TrendRegime) runs[k + 1];
            int end = k + 2 < runs.length ? (Integer) runs[k + 2] : n;
            Arrays.fill(r, start, end, v);
        }
        return r;
    }

    private static Kpis kpis(double[] close, TrendRegime[] r, List<int[]> swing, List<int[]> major) {
        return TrendMixKpi.compute(close, r, null, swing, major, 0, close.length - 1);
    }

    @Test
    void flipFlopsEtSegments() {
        TrendRegime[] r = regimes(400, 0, TrendRegime.UP, 100, TrendRegime.DOWN, 110, TrendRegime.UP,
                200, TrendRegime.DOWN, 300, TrendRegime.UP, 305, TrendRegime.DOWN);
        Kpis k = kpis(new double[400], r, List.of(), List.of());
        assertEquals(5, k.switches());
        assertEquals(1, k.bearInBull().size());
        assertEquals(10, k.bearInBullDays(), EPS);
        assertEquals(1, k.bullInBear().size());
        assertEquals(5, k.bullInBearDays(), EPS);
        assertEquals(10, k.bullInBearWeightedDays(), EPS);
        assertEquals(50, k.medianSegment(), EPS); // segments complets : 5, 10, 90, 100
    }

    @Test
    void rangeEtIndefiniNeSontPasDesSwitchs() {
        TrendRegime[] r = regimes(100, 10, TrendRegime.UP, 40, TrendRegime.RANGE, 60, TrendRegime.UP);
        Kpis k = kpis(new double[100], r, List.of(), List.of());
        assertEquals(0, k.switches());
    }

    @Test
    void retardAuSommetEtAuCreux() {
        double[] c = new double[300];
        for (int i = 0; i < 300; i++) {
            c[i] = i <= 100 ? 100 + i : i <= 200 ? 200 - (i - 100) : 100;
        }
        List<int[]> major = List.of(new int[]{0, ZigZag.LOW}, new int[]{100, ZigZag.HIGH}, new int[]{200, ZigZag.LOW});
        TrendRegime[] r = regimes(300, 0, TrendRegime.UP, 120, TrendRegime.DOWN);
        Kpis k = kpis(c, r, List.of(), major);
        assertEquals(2, k.fronts().size());
        Front bottom = k.fronts().get(0);
        assertFalse(bottom.high());
        assertEquals(0, bottom.lag());
        assertEquals(0.0, bottom.share(), EPS);
        Front top = k.fronts().get(1);
        assertTrue(top.high());
        assertTrue(top.ath());
        assertEquals(120, top.switchDay());
        assertEquals(20, top.lag());
        assertEquals(0.2, top.share(), EPS);
        assertFalse(top.missed());
        assertEquals(20, k.topLag(true), EPS);
    }

    @Test
    void frontJamaisBasculeEstManque() {
        double[] c = new double[300];
        for (int i = 0; i < 300; i++) {
            c[i] = i <= 100 ? 100 + i : i <= 200 ? 200 - (i - 100) : 100;
        }
        List<int[]> major = List.of(new int[]{100, ZigZag.HIGH}, new int[]{200, ZigZag.LOW});
        Kpis k = kpis(c, regimes(300, 0, TrendRegime.UP), List.of(), major);
        assertTrue(k.fronts().getFirst().missed());
        assertEquals(1.0, k.fronts().getFirst().share(), EPS);
    }

    @Test
    void contreSensEtAccord() {
        double[] c = new double[200];
        List<int[]> p = List.of(new int[]{0, ZigZag.LOW}, new int[]{100, ZigZag.HIGH}, new int[]{200 - 1, ZigZag.LOW});
        // Bull sur toute la fenêtre : 100 j de hausse (bon sens) puis 99 j de baisse (à contresens)
        Kpis k = kpis(c, regimes(200, 0, TrendRegime.UP), p, p);
        assertEquals(99, k.bullInDownDays(), EPS);
        assertEquals(0, k.bearInUpDays(), EPS);
        assertEquals(199, k.legDays(), EPS);
        assertEquals(2 * 99.0 / 199, k.counterTrendWeighted(), EPS);
        assertEquals(0.0, k.accord(), EPS); // +1 sur les hausses, -1 sur les baisses
    }

    @Test
    void stabiliteDesFronts() {
        double[] c = new double[300];
        for (int i = 0; i < 300; i++) {
            c[i] = i <= 100 ? 100 + i : i <= 200 ? 200 - (i - 100) : 100;
        }
        List<int[]> major = List.of(new int[]{100, ZigZag.HIGH}, new int[]{200, ZigZag.LOW});
        Kpis a = kpis(c, regimes(300, 0, TrendRegime.UP, 120, TrendRegime.DOWN), List.of(), major);
        Kpis near = kpis(c, regimes(300, 0, TrendRegime.UP, 122, TrendRegime.DOWN), List.of(), major);
        Kpis far = kpis(c, regimes(300, 0, TrendRegime.UP, 130, TrendRegime.DOWN), List.of(), major);
        assertEquals(1.0, TrendMixKpi.frontStability(a, near), EPS);
        assertEquals(0.0, TrendMixKpi.frontStability(a, far), EPS);
    }
}
