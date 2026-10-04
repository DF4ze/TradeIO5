package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests unitaires de {@link SwingStructureCalculator}, réécrits intégralement le 2026-09-19 pour le
 * nouvel algorithme sans ATR/seuil (cf. javadoc de la classe testée) : un pivot se confirme dès la
 * bougie de retournement, plus de notion de warmup/multiplicateur. Toutes les valeurs attendues
 * ci-dessous ont été vérifiées par simulation indépendante de l'algorithme (script Python fidèle à
 * la logique de la classe) avant transcription — rien n'est deviné.
 * <p>
 * Séquence unique de 10 bougies ({@link #SEQ}) couvrant les cas notables : bougie sans effet
 * (candidats inchangés), premier pivot de chaque type (classification {@code null}), classification
 * comparée au pivot confirmé précédent du même type (pas au tout premier), et un cycle complet de
 * régime UNDEFINED -> BEAR_CONFIRMED -> WARNING_BULL_BREAK -> BULL_CONFIRMED.
 */
@DisplayName("SwingStructureCalculator")
class SwingStructureCalculatorTest {

    private final SwingStructureCalculator calculator = new SwingStructureCalculator();

    private static Instant baseTimestamp;

    // (low, high, close) par bougie — cf. javadoc de classe pour le déroulé bougie par bougie.
    //  0: init (candidatHigh=1000, candidatLow=990)
    //  1: ni nouveau haut ni nouveau bas -> rien
    //  2: nouveau haut (1010) seul -> confirme LOW=990 (classification null, 1er pivot low)
    //  3: nouveau haut (1020) seul -> confirme LOW=995 (HL, 995>990)
    //  4: nouveau bas (960) seul -> confirme HIGH=1020 (classification null, 1er pivot high)
    //  5: ni l'un ni l'autre -> rien
    //  6: nouveau bas (940) seul -> confirme HIGH=1015 (LH, 1015<1020)
    //  7: nouveau haut (1050) seul -> confirme LOW=940 (LL, 940<995 : comparé au précédent low
    //     confirmé (995), pas au tout premier (990)) -> régime BEAR_CONFIRMED (LH+LL)
    //  8: nouveau haut (1060) seul -> confirme LOW=945 (HL, 945>940) -> une seule jambe casse ->
    //     WARNING_BULL_BREAK (la jambe high reste LH)
    //  9: nouveau bas (900) seul -> confirme HIGH=1060 (HH, 1060>1015) -> les deux jambes
    //     confirment bull -> BULL_CONFIRMED
    private static final double[][] SEQ = {
            {990, 1000, 995},
            {990, 1000, 995},
            {995, 1010, 1005},
            {998, 1020, 1015},
            {960, 1015, 970},
            {965, 990, 985},
            {940, 985, 945},
            {945, 1050, 1040},
            {950, 1060, 1055},
            {900, 1055, 910},
    };

    @BeforeAll
    static void init() {
        baseTimestamp = Instant.parse("2024-01-01T00:00:00Z");
    }

    private static List<MarketData> candles(double[][] ohlc) {
        List<MarketData> candles = new ArrayList<>();
        for (int i = 0; i < ohlc.length; i++) {
            candles.add(MarketData.builder()
                    .low(BigDecimal.valueOf(ohlc[i][0]))
                    .high(BigDecimal.valueOf(ohlc[i][1]))
                    .close(BigDecimal.valueOf(ohlc[i][2]))
                    .timestamp(baseTimestamp.plus(i, ChronoUnit.DAYS))
                    .build());
        }
        return candles;
    }

    private static double price(SwingPivot pivot) {
        return pivot.price().doubleValue();
    }

    @Test
    @DisplayName("Liste vide -> UNDEFINED, pivots null, pas d'exception")
    void emptyHistory_returnsUndefined() {
        SwingStructureState state = assertDoesNotThrow(() -> calculator.compute(List.of()));

        assertEquals(SwingStructureRegime.UNDEFINED, state.regime());
        assertNull(state.lastSwingHigh());
        assertNull(state.lastSwingLow());
        assertTrue(state.nearestSrLevels().isEmpty());
    }

    @Test
    @DisplayName("Une seule bougie -> UNDEFINED, aucun pivot ne peut se confirmer (rien à comparer)")
    void singleCandle_returnsUndefined() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 1));

        assertEquals(SwingStructureRegime.UNDEFINED, state.regime());
        assertNull(state.lastSwingHigh());
        assertNull(state.lastSwingLow());
    }

    @Test
    @DisplayName("Bougie sans nouveau haut ni nouveau bas -> aucun pivot confirmé")
    void candleWithoutNewExtreme_confirmsNothing() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 2));

        assertNull(state.lastSwingHigh());
        assertNull(state.lastSwingLow());
    }

    @Test
    @DisplayName("Premier pivot low confirmé (bougie 3, index 2) : classification null, rien à comparer")
    void firstLowPivot_hasNullClassification() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 3));

        assertEquals(990.0, price(state.lastSwingLow()));
        assertNull(state.lastSwingLow().classification());
        assertNull(state.lastSwingHigh());
    }

    @Test
    @DisplayName("Premier pivot high confirmé (bougie 5, index 4) : classification null, rien à comparer")
    void firstHighPivot_hasNullClassification() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 5));

        assertEquals(1020.0, price(state.lastSwingHigh()));
        assertNull(state.lastSwingHigh().classification());
    }

    @Test
    @DisplayName("Une confirmation par bougie au maximum : jamais un pivot high ET low sur la même bougie")
    void atMostOnePivotConfirmedPerCandle() {
        List<SwingStructureSnapshot> timeline = calculator.computeTimeline(candles(SEQ));

        for (SwingStructureSnapshot snapshot : timeline) {
            assertTrue(snapshot.pivotsConfirmedThisCandle().size() <= 1,
                    "au plus 1 pivot confirmé par bougie, jamais 2 (contrairement à l'ancienne version ATR)");
        }
    }

    @Test
    @DisplayName("BEAR_CONFIRMED : dernier pivot high = LH, dernier pivot low = LL")
    void bearConfirmed() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 8));

        assertEquals(SwingStructureRegime.BEAR_CONFIRMED, state.regime());
        assertEquals(PivotClassification.LH, state.lastSwingHigh().classification());
        assertEquals(1015.0, price(state.lastSwingHigh()));
        assertEquals(PivotClassification.LL, state.lastSwingLow().classification());
        assertEquals(940.0, price(state.lastSwingLow()));
    }

    @Test
    @DisplayName("Classification comparée au pivot confirmé précédent du même type, pas au tout premier")
    void classification_comparesToImmediatelyPrecedingPivotOfSameType() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 8));

        // 940 < 995 (son prédécesseur immédiat, confirmé bougie 4) -> LL, alors que 940 < 990
        // (le tout premier pivot low) donnerait la même conclusion par coïncidence : le vrai test
        // est previousSwingLow == 995, pas 990.
        assertEquals(995.0, price(state.previousSwingLow()));
    }

    @Test
    @DisplayName("Depuis BEAR_CONFIRMED, une seule jambe casse -> WARNING_BULL_BREAK")
    void warningBullBreak() {
        SwingStructureState state = calculator.compute(candles(SEQ).subList(0, 9));

        assertEquals(SwingStructureRegime.WARNING_BULL_BREAK, state.regime());
        assertEquals(PivotClassification.HL, state.lastSwingLow().classification());
        assertEquals(945.0, price(state.lastSwingLow()));
        // La jambe high n'a pas bougé depuis le BEAR_CONFIRMED précédent.
        assertEquals(PivotClassification.LH, state.lastSwingHigh().classification());
    }

    @Test
    @DisplayName("La jambe manquante confirme à son tour dans le sens du warning -> bascule en BULL_CONFIRMED")
    void warningBullBreak_flipsToBullConfirmedWhenOtherLegConfirms() {
        SwingStructureState state = calculator.compute(candles(SEQ));

        assertEquals(SwingStructureRegime.BULL_CONFIRMED, state.regime());
        assertEquals(PivotClassification.HH, state.lastSwingHigh().classification());
        assertEquals(1060.0, price(state.lastSwingHigh()));
        assertEquals(PivotClassification.HL, state.lastSwingLow().classification());
        assertEquals(945.0, price(state.lastSwingLow()));
        assertEquals(PivotClassification.LH, state.previousSwingHigh().classification());
        assertEquals(1015.0, price(state.previousSwingHigh()));
    }

    @Test
    @DisplayName("nearestSrLevels : triés par proximité au dernier close, limités à 4, malgré plus de 4 pivots confirmés")
    void nearestSrLevels_sortedByProximityAndLimitedToFour() {
        SwingStructureState state = calculator.compute(candles(SEQ));

        // 7 pivots confirmés au total sur cette série (990, 995, 1020, 1015, 940, 945, 1060).
        // Dernier close de la série = 910.
        assertEquals(4, state.nearestSrLevels().size());
        List<Double> asDouble = state.nearestSrLevels().stream().map(BigDecimal::doubleValue).toList();
        assertEquals(List.of(940.0, 945.0, 990.0, 995.0), asDouble);

        double lastClose = 910.0;
        for (int i = 1; i < asDouble.size(); i++) {
            double distancePrev = Math.abs(asDouble.get(i - 1) - lastClose);
            double distanceCurrent = Math.abs(asDouble.get(i) - lastClose);
            assertTrue(distancePrev <= distanceCurrent, "nearestSrLevels doit être trié par distance croissante au dernier close");
        }
    }
}
