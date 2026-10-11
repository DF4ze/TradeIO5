package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RainbowLiveDefaultPresets / RainbowAtrConfig")
class RainbowLiveDefaultPresetsTest {

    @Test
    @DisplayName("toTuning()/toGlobals() : round-trip identique aux records du bench")
    void roundTrip() {
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
            RainbowAtrConfig again = RainbowAtrConfig.of(c.toTuning(), c.toGlobals());

            assertEquals(c, again, asset);
            assertEquals(c.toTuning(), again.toTuning(), asset);
            assertEquals(c.toGlobals(), again.toGlobals(), asset);
            assertTrue(c.toTuning().isValid(), asset);
        }
        RainbowAtrTuning t = RainbowAtrTuning.pineFinalCandidate();
        RainbowAtrGlobals g = RainbowAtrGlobals.pineDefault();
        assertEquals(t, RainbowAtrConfig.of(t, g).toTuning());
        assertEquals(g, RainbowAtrConfig.of(t, g).toGlobals());
    }

    @Test
    @DisplayName("BTC = jeu global recommandé du bench (moon OFF)")
    void btc() {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");

        assertEquals(new RainbowAtrTuning(50, 21, 5.0, 0.5, 2.0, 3.0, 3.5,
                ReentryMode.TRAILING_STOP, ReentryMode.FIXED_DELAY, 3.0, 5.0, 1, 15, 0.25, false, true, true), c.toTuning());
        assertEquals(new RainbowAtrGlobals(true, 30.0, 15.0, 0.25, 4.0, 1.0, 1.0,
                false, 0.0, false, 15.0, 100.0, 2.0, 1.0, 0.5, 3.0, 1.0), c.toGlobals());
    }

    @Test
    @DisplayName("ETH et PAXG = optimum global plein échantillon du bench")
    void ethPaxg() {
        RainbowAtrConfig eth = RainbowLiveDefaultPresets.configFor("ETH");
        assertEquals(new RainbowAtrTuning(36, 21, 4.0, 0.5, 2.0, 3.0, 3.5,
                ReentryMode.FIXED_DELAY, ReentryMode.FIXED_DELAY, 3.0, 5.0, 7, 3, 0.20, false, true, true), eth.toTuning());
        assertEquals(new RainbowAtrGlobals(true, 60.0, 15.0, 0.0, 5.0, 3.0, 1.0,
                true, 50.0, false, 10.0, 50.0, 2.0, 1.0, 0.5, 3.0, 1.0), eth.toGlobals());

        RainbowAtrConfig paxg = RainbowLiveDefaultPresets.configFor("PAXG");
        assertEquals(new RainbowAtrTuning(36, 28, 6.0, 0.5, 2.0, 2.0, 3.0,
                ReentryMode.FIXED_DELAY, ReentryMode.TRAILING_STOP, 5.0, 2.0, 1, 10, 0.20, true, true, true), paxg.toTuning());
        assertEquals(new RainbowAtrGlobals(true, 15.0, 10.0, 1.0, 5.0, 2.0, 0.5,
                true, 75.0, false, 8.0, 50.0, 2.0, 1.0, 0.5, 3.0, 1.0), paxg.toGlobals());
    }

    @Test
    @DisplayName("configFor renvoie une copie ; actif non autorisé refusé ; hash stable et sensible aux changements")
    void copyAndHash() {
        RainbowAtrConfig a = RainbowLiveDefaultPresets.configFor("BTC");
        RainbowAtrConfig b = RainbowLiveDefaultPresets.configFor("BTC");

        assertNotSame(a, b);
        assertEquals(a.hash(), b.hash());
        b.setSmaPeriod(51);
        assertNotEquals(a.hash(), b.hash());
        assertThrows(IllegalArgumentException.class, () -> RainbowLiveDefaultPresets.configFor("SOL"));
    }
}
