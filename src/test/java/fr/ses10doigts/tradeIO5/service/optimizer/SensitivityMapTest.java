package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.ParamReport;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.Point;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Carte de sensibilité : valeur en place = comparateur, ordre des bornes ATR, reproductibilité, grille de valeurs. */
class SensitivityMapTest {

    private static ScorerInputs inputs;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), 10_000.0, PresetOptimizerTestSupport.END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
    }

    private static List<SensitivityParam> some(String... keys) {
        return SensitivityParam.ALL.stream().filter(p -> List.of(keys).contains(p.key())).toList();
    }

    @Test
    @DisplayName("La valeur en place d'un balayage reproduit le comparateur (preset en place) à l'identique, Bull comme Bear")
    void inPlaceValueIsTheComparator() {
        SensitivityReport r = SensitivityMap.run(inputs, 24, some("sellFraction", "moonOn", "buyReentryMode", "athBuyMax"));
        for (SensitivityReport.SetReport set : r.sets()) {
            assertEquals(4, set.params().size());
            for (ParamReport p : set.params()) {
                Point inPlace = p.curve().stream().filter(Point::inPlace).findFirst().orElseThrow();
                assertEquals(r.baseline().preset24m(), inPlace.reading(), set.role() + "." + p.key());
                assertEquals(set.role().equals("BULL") ? r.baseline().frozenBull() : r.baseline().frozenBear(), inPlace.frozen());
                assertTrue(p.spreadPnl() >= 0);
                assertTrue(p.bestVsPresetPnl() >= 0);
            }
        }
    }

    @Test
    @DisplayName("Ordre des bornes ATR : une variante qui le viole est ignorée et listée, jamais rejouée")
    void atrOrderIsRespected() {
        RainbowAtrParamSet bear = RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BEAR);
        RainbowAtrParamSet broken = SensitivityParam.ALL.stream().filter(p -> p.key().equals("atrMultUp1")).findFirst().orElseThrow()
                .apply(bear, 19.0);
        assertNotNull(SensitivityMap.violation(broken, 6));
        assertNull(SensitivityMap.violation(bear, 6));
        SensitivityReport r = SensitivityMap.run(inputs, 24, some("atrMultUp3", "atrMultDown2", "atrMultDown1"));
        for (SensitivityReport.SetReport set : r.sets()) {
            long ignoredPoints = set.params().stream().flatMap(p -> p.curve().stream()).filter(x -> x.invalidReason() != null).count();
            assertEquals(ignoredPoints, set.skipped().size());
            set.params().stream().flatMap(p -> p.curve().stream()).filter(x -> x.invalidReason() != null).forEach(x -> {
                assertNull(x.reading());
                assertNull(x.frozen());
            });
            set.params().stream().flatMap(p -> p.curve().stream()).filter(x -> x.reading() != null)
                    .forEach(x -> assertFalse(x.inPlace() && x.invalidReason() != null));
        }
        assertEquals(r.multiplicity().ignoredCombinations(), r.sets().stream().mapToInt(s -> s.skipped().size()).sum());
    }

    @Test
    @DisplayName("Mêmes entrées => même rapport (hors durées) ; l'autre jeu reste celui du preset en place")
    void reproducible() {
        List<SensitivityParam> params = some("trailingStopSellPct", "athOn", "multX2");
        SensitivityReport a = SensitivityMap.run(inputs, 24, params);
        SensitivityReport b = SensitivityMap.run(inputs, 24, params);
        assertEquals(a.toJsonWithoutTimings(), b.toJsonWithoutTimings());
        assertNotNull(a.inputs().fingerprint().checksum());
        assertEquals(a.multiplicity().replays(), b.multiplicity().replays());
        assertTrue(a.toMarkdown().contains("BULL seul"));
        assertTrue(a.toMarkdown().contains("BEAR seul"));
    }

    @Test
    @DisplayName("Grille de valeurs : 9 points max, valeur en place incluse, croissants ; interrupteurs et modes : une variante par option")
    void valueGrid() {
        RainbowAtrParamSet bull = RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BULL);
        for (SensitivityParam p : SensitivityParam.ALL) {
            List<SensitivityParam.Value> v = p.values(bull);
            assertEquals(1, v.stream().filter(SensitivityParam.Value::inPlace).count(), p.key());
            switch (p.kind()) {
                case BOOLEAN -> assertEquals(2, v.size());
                case ENUM -> assertEquals(3, v.size());
                default -> {
                    assertTrue(v.size() <= SensitivityParam.GRID && v.size() >= 2, p.key());
                    for (int i = 1; i < v.size(); i++) {
                        assertTrue(((Number) v.get(i).raw()).doubleValue() > ((Number) v.get(i - 1).raw()).doubleValue(), p.key());
                    }
                    assertTrue(((Number) v.getFirst().raw()).doubleValue() <= p.min() + 1e-9 || v.getFirst().inPlace(), p.key());
                }
            }
        }
    }
}
