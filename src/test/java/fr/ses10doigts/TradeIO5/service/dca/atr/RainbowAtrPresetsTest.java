package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Preset « Perso Generic (tous actifs) » de {@code rainbow_dca_v4_atr_moon.pine} (SOURCE DE VÉRITÉ). */
class RainbowAtrPresetsTest {

    @Test
    void genericReprendLesValeursDuPine() {
        RainbowAtrParamSet g = RainbowAtrPresets.generic();
        RainbowAtrTuning t = g.tuning();
        assertEquals(36, t.smaPeriod());
        assertEquals(14, t.atrPeriod());
        assertEquals(1.0, t.atrMultDown2());
        assertEquals(0.0, t.atrMultDown1());
        assertEquals(0.0, t.atrMultUp1());
        assertEquals(0.0, t.atrMultUp2());
        assertEquals(2.5, t.atrMultUp3());
        assertEquals(ReentryMode.TRAILING_STOP, t.buyReentryMode());
        assertEquals(ReentryMode.TRAILING_STOP, t.sellReentryMode());
        assertEquals(3.0, t.trailingStopBuyPct());
        assertEquals(5.0, t.trailingStopSellPct());
        assertEquals(10, t.cooldownDays());
        assertEquals(15, t.fixedDelayDays());
        assertEquals(0.2, t.sellFraction());
        assertFalse(t.allowSellDuringCooldown());
        assertTrue(t.cooldownAfterSellOn());
        assertTrue(t.blockBuyAfterSellUntilDown2());
        assertTrue(t.isValid());

        RainbowAtrGlobals o = g.globals();
        assertTrue(o.athOn());
        assertEquals(60.0, o.athRefDdBuyPct());
        assertEquals(30.0, o.athRefDdSellPct());
        assertEquals(0.5, o.athBuyMin());
        assertEquals(4.0, o.athBuyMax());
        assertEquals(4.0, o.athSellMax());
        assertEquals(1.0, o.athSellMin());
        assertTrue(o.moonOn());
        assertEquals(30.0, o.moonReservePct());
        assertFalse(o.moonReserveRatchet());
        assertEquals(9.0, o.moonTrailingStopPct());
        assertEquals(100.0, o.moonStopSellPct());
    }
}
