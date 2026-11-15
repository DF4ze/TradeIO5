package fr.ses10doigts.tradeIO5.service.expert.simulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DrawdownMeter : drawdown du wallet (mesure pure)")
class DrawdownMeterTest {

    private static final LocalDate D0 = LocalDate.parse("2025-01-01");

    private static List<LocalDate> days(int n) {
        List<LocalDate> d = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            d.add(D0.plusDays(i));
        }
        return d;
    }

    private static DrawdownMeter.Measure m(double... v) {
        return DrawdownMeter.measure(days(v.length), java.util.Arrays.stream(v).boxed().toList());
    }

    @Test
    @DisplayName("Série toujours croissante : drawdown nul, aucune date")
    void rising() {
        DrawdownMeter.Measure r = m(100, 101, 105, 110);
        assertEquals(0.0, r.maxPct());
        assertNull(r.peakDay());
        assertNull(r.troughDay());
        assertNull(r.recoveryDay());
        assertEquals(0, r.longestUnderwaterDays());
        assertEquals(0.0, r.lastPct());
    }

    @Test
    @DisplayName("Une baisse puis retour au plus haut : dates et durée")
    void dipAndRecovery() {
        DrawdownMeter.Measure r = m(100, 120, 90, 100, 120, 130);
        assertEquals(-25.0, r.maxPct(), 1e-9);
        assertEquals(D0.plusDays(1), r.peakDay());
        assertEquals(D0.plusDays(2), r.troughDay());
        assertEquals(D0.plusDays(4), r.recoveryDay());
        assertEquals(2, r.longestUnderwaterDays());
        assertEquals(0.0, r.lastPct());
    }

    @Test
    @DisplayName("Deux baisses, la seconde plus profonde : c'est elle qui est retenue")
    void deeperSecondDrop() {
        DrawdownMeter.Measure r = m(100, 90, 100, 130, 65, 130);
        assertEquals(-50.0, r.maxPct(), 1e-9);
        assertEquals(D0.plusDays(3), r.peakDay());
        assertEquals(D0.plusDays(4), r.troughDay());
        assertEquals(D0.plusDays(5), r.recoveryDay());
    }

    @Test
    @DisplayName("Jamais de retour au plus haut : pas de date de retour, durée jusqu'à la fin")
    void noRecovery() {
        DrawdownMeter.Measure r = m(100, 80, 70, 90);
        assertEquals(-30.0, r.maxPct(), 1e-9);
        assertNull(r.recoveryDay());
        assertEquals(3, r.longestUnderwaterDays());
        assertEquals(-10.0, r.lastPct(), 1e-9);
    }

    @Test
    @DisplayName("Série constante et un seul point : drawdown nul")
    void flatAndSingle() {
        assertEquals(0.0, m(50, 50, 50).maxPct());
        DrawdownMeter.Measure one = m(42);
        assertEquals(0.0, one.maxPct());
        assertEquals(1, one.drawdownPct().size());
    }

    @Test
    @DisplayName("Valeur nulle, négative, non finie, série vide ou tailles différentes : erreur explicite")
    void invalid() {
        assertThrows(IllegalArgumentException.class, () -> m(100, 0, 90));
        assertThrows(IllegalArgumentException.class, () -> m(100, -5));
        assertThrows(IllegalArgumentException.class, () -> m(100, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> DrawdownMeter.measure(days(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DrawdownMeter.measure(days(2), List.of(1.0, 2.0, 3.0)));
    }

    @Test
    @DisplayName("Propriétés : ∈ [-100 ; 0], max = minimum de la série, invariant par changement d'échelle")
    void properties() {
        Random rnd = new Random(7);
        for (int run = 0; run < 50; run++) {
            int n = 2 + rnd.nextInt(200);
            List<Double> v = new ArrayList<>();
            double x = 1000;
            for (int i = 0; i < n; i++) {
                x *= 1 + (rnd.nextDouble() - 0.5) * 0.2;
                v.add(x);
            }
            DrawdownMeter.Measure a = DrawdownMeter.measure(days(n), v);
            assertTrue(a.drawdownPct().stream().allMatch(p -> p <= 0 && p > -100));
            assertEquals(a.drawdownPct().stream().min(Double::compare).orElseThrow(), a.maxPct(), 1e-12);
            double k = 1 + rnd.nextInt(1000);
            DrawdownMeter.Measure b = DrawdownMeter.measure(days(n), v.stream().map(y -> y * k).toList());
            for (int i = 0; i < n; i++) {
                assertEquals(a.drawdownPct().get(i), b.drawdownPct().get(i), 1e-9);
            }
        }
    }

    @Test
    @DisplayName("Verdict : égalité exacte au plafond = respecté ; 1er jour de dépassement ; dépassement max en points")
    void verdicts() {
        List<LocalDate> d = days(4);
        DrawdownMeter.Measure r = DrawdownMeter.measure(d, List.of(100.0, 75.0, 60.0, 100.0));
        DrawdownMeter.Verdict equal = DrawdownMeter.verdict(d, DrawdownMeter.measure(d, List.of(100.0, 75.0, 80.0, 100.0)), -25.0);
        assertTrue(equal.respected());
        assertNull(equal.firstBreachDay());
        assertEquals(0.0, equal.maxOverrunPts());
        DrawdownMeter.Verdict over = DrawdownMeter.verdict(d, r, -25.0);
        assertFalse(over.respected());
        assertEquals(D0.plusDays(2), over.firstBreachDay());
        assertEquals(15.0, over.maxOverrunPts(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> DrawdownMeter.verdict(d, r, 5.0));
        assertThrows(IllegalArgumentException.class, () -> DrawdownMeter.verdict(d, r, -101.0));
    }

    @Test
    @DisplayName("Verdicts monotones : un plafond plus serré est dépassé dès qu'un plafond plus large l'est")
    void monotone() {
        Random rnd = new Random(3);
        for (int run = 0; run < 50; run++) {
            int n = 5 + rnd.nextInt(100);
            List<Double> v = new ArrayList<>();
            double x = 100;
            for (int i = 0; i < n; i++) {
                x *= 1 + (rnd.nextDouble() - 0.5) * 0.3;
                v.add(x);
            }
            List<LocalDate> d = days(n);
            DrawdownMeter.Measure r = DrawdownMeter.measure(d, v);
            boolean wideBreached = false;
            for (double cap : new double[]{-60, -40, -25, -15}) { // du plus large au plus serré
                boolean breached = !DrawdownMeter.verdict(d, r, cap).respected();
                assertTrue(breached || !wideBreached, "plafond " + cap + " respecté alors qu'un plus large est dépassé");
                wideBreached |= breached;
            }
        }
    }
}
