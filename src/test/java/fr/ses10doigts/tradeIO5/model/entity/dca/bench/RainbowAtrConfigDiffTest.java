package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RainbowAtrConfig.diff")
class RainbowAtrConfigDiffTest {

    private static RainbowAtrConfig btc() {
        return RainbowLiveDefaultPresets.configFor("BTC");
    }

    @Test
    @DisplayName("configs identiques => aucun diff")
    void noDiff() {
        assertTrue(btc().diff(btc()).isEmpty());
    }

    @Test
    @DisplayName("1 paramètre tuning => son nom nu")
    void oneTuningParam() {
        RainbowAtrConfig other = btc();
        other.setSmaPeriod(36);
        assertEquals(List.of("smaPeriod"), btc().diff(other));
    }

    @Test
    @DisplayName("1 paramètre global (dont baseAmount) => nom préfixé globals.")
    void oneGlobalParam() {
        RainbowAtrConfig a = btc();
        RainbowAtrConfig b = btc();
        b.setAthRefDdBuyPct(a.getAthRefDdBuyPct() + 5);
        assertEquals(List.of("globals.athRefDdBuyPct"), a.diff(b));
        b = btc();
        b.setBaseAmount(7);
        assertEquals(List.of("globals.baseAmount"), a.diff(b));
    }

    @Test
    @DisplayName("plusieurs paramètres (enum, boolean, tuning + global) => ordre de déclaration, symétrique")
    void severalParams() {
        RainbowAtrConfig a = btc();
        RainbowAtrConfig b = btc();
        b.setBuyReentryMode(ReentryMode.FIXED_DELAY);
        b.setAllowSellDuringCooldown(!a.isAllowSellDuringCooldown());
        b.setMoonOn(!a.isMoonOn());
        List<String> expected = List.of("buyReentryMode", "allowSellDuringCooldown", "globals.moonOn");
        assertEquals(expected, a.diff(b));
        assertEquals(expected, b.diff(a));
    }
}
