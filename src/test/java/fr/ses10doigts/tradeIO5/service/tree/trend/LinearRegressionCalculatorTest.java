package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LinearRegressionCalculator")
class LinearRegressionCalculatorTest {

    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");
    private static final double DELTA = 1e-9;

    private final LinearRegressionCalculator calculator = new LinearRegressionCalculator();

    private static List<MarketData> closes(double... values) {
        List<MarketData> data = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            data.add(MarketData.builder()
                    .close(BigDecimal.valueOf(values[i]))
                    .timestamp(BASE.plus(i, ChronoUnit.DAYS))
                    .build());
        }
        return data;
    }

    @Test
    @DisplayName("série parfaitement linéaire croissante -> pente exacte, r2=1, regressionValue = dernière clôture")
    void compute_perfectlyLinearIncreasing() {
        // y = 10 + 2x sur x=0..4 : 10,12,14,16,18
        LinearRegressionResult result = calculator.compute(closes(10, 12, 14, 16, 18), 5);

        assertEquals(2.0, result.slope(), DELTA);
        assertEquals(2.0 / 14.0, result.normalizedSlope(), DELTA);
        assertEquals(1.0, result.r2(), DELTA);
        assertEquals(18.0, result.regressionValue(), DELTA);
    }

    @Test
    @DisplayName("série parfaitement linéaire décroissante -> pente négative exacte, r2=1")
    void compute_perfectlyLinearDecreasing() {
        // y = 20 - 5x sur x=0..4 : 20,15,10,5,0
        LinearRegressionResult result = calculator.compute(closes(20, 15, 10, 5, 0), 5);

        assertEquals(-5.0, result.slope(), DELTA);
        assertEquals(-0.5, result.normalizedSlope(), DELTA);
        assertEquals(1.0, result.r2(), DELTA);
        assertEquals(0.0, result.regressionValue(), DELTA);
    }

    @Test
    @DisplayName("série parfaitement plate -> pente nulle, r2=0 par convention (aucune variance à expliquer)")
    void compute_flat() {
        LinearRegressionResult result = calculator.compute(closes(10, 10, 10, 10, 10), 5);

        assertEquals(0.0, result.slope(), DELTA);
        assertEquals(0.0, result.normalizedSlope(), DELTA);
        assertEquals(0.0, result.r2(), DELTA);
        assertEquals(10.0, result.regressionValue(), DELTA);
    }

    @Test
    @DisplayName("série bruitée -> r2 strictement entre 0 et 1")
    void compute_noisy() {
        // tendance haussière avec un aller-retour ponctuel
        LinearRegressionResult result = calculator.compute(closes(10, 20, 15, 30, 40), 5);

        assertTrue(result.slope() > 0, "expected positive slope, got " + result.slope());
        assertTrue(result.r2() > 0.0 && result.r2() < 1.0, "expected 0 < r2 < 1, got " + result.r2());
    }

    @Test
    @DisplayName("moins de bougies que period -> IllegalArgumentException")
    void compute_notEnoughCandles_throws() {
        assertThrows(IllegalArgumentException.class, () -> calculator.compute(closes(10, 20), 5));
    }

    @Test
    @DisplayName("candles null -> IllegalArgumentException")
    void compute_nullCandles_throws() {
        assertThrows(IllegalArgumentException.class, () -> calculator.compute(null, 5));
    }

    @Test
    @DisplayName("period < 2 -> IllegalArgumentException")
    void compute_periodTooSmall_throws() {
        assertThrows(IllegalArgumentException.class, () -> calculator.compute(closes(10, 20, 30), 1));
    }

    @Test
    @DisplayName("computeTimeline : null tant que l'historique est insuffisant, puis cohérent avec compute() fenêtre par fenêtre")
    void computeTimeline_consistentWithCompute() {
        // y = 10 + 2x sur x=0..6 : 10,12,14,16,18,20,22
        List<MarketData> candles = closes(10, 12, 14, 16, 18, 20, 22);
        int period = 5;

        List<LinearRegressionSnapshot> timeline = calculator.computeTimeline(candles, period);

        assertEquals(candles.size(), timeline.size());
        for (int i = 0; i < period - 1; i++) {
            assertNull(timeline.get(i), "index " + i + " should be null (warmup)");
        }

        for (int i = period - 1; i < candles.size(); i++) {
            LinearRegressionResult expected = calculator.compute(candles.subList(0, i + 1), period);
            LinearRegressionSnapshot snapshot = timeline.get(i);

            assertNotNull(snapshot, "index " + i + " should not be null");
            assertEquals(expected.regressionValue(), snapshot.regressionValue(), DELTA);
            assertEquals(expected.normalizedSlope(), snapshot.normalizedSlope(), DELTA);
            assertEquals(expected.r2(), snapshot.r2(), DELTA);
            assertEquals(candles.get(i).getTimestamp(), snapshot.timestamp());
        }
    }

    @Test
    @DisplayName("computeTimeline sur liste vide -> timeline vide")
    void computeTimeline_emptyCandles() {
        List<LinearRegressionSnapshot> timeline = calculator.computeTimeline(List.of(), 5);
        assertTrue(timeline.isEmpty());
    }
}
