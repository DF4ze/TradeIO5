package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AtCap;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Point;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchCutTest {

    private static final double CASH = 10_000.0;
    private static ScorerInputs inputs;
    private static SensitivityEvaluator ev;
    private static RainbowAtrParamSet[] sets;

    @BeforeAll
    static void load() throws IOException {
        List<MarketData> candles = PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE);
        var link = PresetOptimizerTestSupport.link("BTC", candles);
        inputs = ScorerInputs.of(List.of(link), CASH, PresetOptimizerTestSupport.END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        ev = new SensitivityEvaluator(inputs, 48);
        sets = SwitchCutStudy.withoutAth(ev.template().sets());
    }

    @Test
    void withoutAthOnlyTurnsAthOff() {
        RainbowAtrParamSet[] orig = ev.template().sets();
        for (int i = 0; i < orig.length; i++) {
            assertFalse(sets[i].globals().athOn());
            assertEquals(orig[i].globals().baseAmount(), sets[i].globals().baseAmount());
            assertEquals(orig[i].tuning(), sets[i].tuning());
        }
    }

    @Test
    void noCutAndNeutralCutGiveIdenticalNumbers() {
        var none = ev.parityCut(sets, false, 0, null, null, null, null);
        var neutral = ev.parityCut(sets, false, 0, null, null, null, new MultiLinkReplayEngine.SwitchCut(0.0, 0.0));
        assertNotNull(none);
        assertNotNull(neutral);
        assertEquals(none.parity().preset().finalValue(), neutral.parity().preset().finalValue(), 1e-9);
        assertEquals(0, none.cutSells());
        assertEquals(0, neutral.cutSells());
        assertEquals(none.parity().preset().finalValue(), ev.parity(sets, false, 0, null, null).preset().finalValue(), 1e-9);
    }

    @Test
    void fullCutSellsAtSwitchesAndChangesTheOutcome() {
        var none = ev.parityCut(sets, false, 0, null, null, null, null);
        var cut = ev.parityCut(sets, false, 0, null, null, null, new MultiLinkReplayEngine.SwitchCut(1.0, 20.0));
        assertTrue(cut.cutSells() > 0, "au moins un switch Bull → Bear sur 48 mois");
        assertTrue(cut.cutBuys() > 0, "au moins une ré-entrée");
        assertTrue(Math.abs(cut.parity().preset().finalValue() - none.parity().preset().finalValue()) > 1e-6);
        assertTrue(cut.cutCostUsdc() > 0);
    }

    @Test
    void atCapInterpolatesAndFlagsUnbracketedCaps() {
        List<Level> levels = List.of(level(1, -10, 20), level(2, -30, 40));
        AtCap a = SwitchCutStudy.atCap(levels, -20.0, Level::pure);
        assertEquals("INTERPOLE", a.status());
        assertEquals(1.5, a.multiplier(), 1e-9);
        assertEquals(40.0, a.medianGain(), 1e-9);
        assertEquals("SOUS_LE_MINIMUM", SwitchCutStudy.atCap(levels, -5.0, Level::pure).status());
        AtCap high = SwitchCutStudy.atCap(levels, -50.0, Level::pure);
        assertEquals("PLAFOND_NON_ATTEINT", high.status());
        assertNull(high.multiplier());
    }

    @Test
    void compareAppliesTheFixedRule() {
        ParityConfig pc = ParityConfig.defaults(PresetOptimizerTestSupport.END);
        AtCap pure = new AtCap("INTERPOLE", 2.0, 50.0, List.of(10.0, 10.0, 10.0, 10.0), 10.0);
        AtCap better = new AtCap("INTERPOLE", 2.0, 50.0, List.of(12.0, 12.0, 12.0, 5.0), 12.0);
        AtCap worse = new AtCap("INTERPOLE", 2.0, 50.0, List.of(12.0, 12.0, 5.0, 5.0), 8.5);
        assertEquals("UTILE", SwitchCutStudy.compare("X", -25.0, pure, better, pc).verdict());
        assertEquals("NON_UTILE", SwitchCutStudy.compare("X", -25.0, pure, worse, pc).verdict());
        assertEquals("NON_CONCLU", SwitchCutStudy.compare("X", -25.0, pure, new AtCap("PLAFOND_NON_ATTEINT", null, null, List.of(), null), pc).verdict());
    }

    private static Level level(double m, double dd, double gain) {
        List<Point> ps = List.of(new Point(gain, dd, 50.0), new Point(gain + 20, dd, 50.0));
        return new Level(m, ps, ps, 0, 0, 0.0);
    }
}
