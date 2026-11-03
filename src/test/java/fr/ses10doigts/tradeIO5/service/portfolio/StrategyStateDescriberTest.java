package fr.ses10doigts.tradeIO5.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.ses10doigts.tradeIO5.model.dto.portfolio.StrategyStateDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;

@DisplayName("StrategyStateDescriber : état de la machine Rainbow pour le Journal")
class StrategyStateDescriberTest {

    private static RainbowLivePassBlock.RainbowLivePassBlockBuilder signal() {
        return RainbowLivePassBlock.builder().close(100.0).boundDown2(80.0).zone(3).buyArmed(false).sellArmed(false)
                .buyLocked(false).moonMode(false).cooldownRemaining(0);
    }

    private static RainbowAtrConfig config() {
        RainbowAtrConfig c = new RainbowAtrConfig();
        c.setTrailingStopBuyPct(5);
        c.setTrailingStopSellPct(10);
        c.setMoonTrailingStopPct(20);
        return c;
    }

    @Test
    @DisplayName("passe non jouée => pas d'état")
    void noPass() {
        assertNull(StrategyStateDescriber.describe(null, null, config()));
    }

    @Test
    @DisplayName("achat armé => trailing d'achat : plus bas × (1 + %)")
    void buyTrailing() {
        RainbowLiveEngineState e = RainbowLiveEngineState.builder().lowestSinceArmed(90.0).buyArmedDays(2).build();
        StrategyStateDto s = StrategyStateDescriber.describe(signal().buyArmed(true).build(), e, config());
        assertEquals("BUY_TRAILING", s.kind());
        assertEquals(90.0, s.referencePrice());
        assertEquals(94.5, s.triggerPrice(), 1e-9);
        assertEquals(2, s.armedDays());
    }

    @Test
    @DisplayName("vente armée => trailing de vente : plus haut × (1 − %), prioritaire sur l'achat")
    void sellTrailing() {
        RainbowLiveEngineState e = RainbowLiveEngineState.builder().highestSinceArmed(200.0).build();
        StrategyStateDto s = StrategyStateDescriber.describe(signal().sellArmed(true).buyArmed(true).build(), e, config());
        assertEquals("SELL_TRAILING", s.kind());
        assertEquals(180.0, s.triggerPrice(), 1e-9);
    }

    @Test
    @DisplayName("cooldown prioritaire sur le verrou, qui reste signalé")
    void cooldownThenLock() {
        StrategyStateDto s = StrategyStateDescriber.describe(signal().cooldownRemaining(3).buyLocked(true).build(), null, config());
        assertEquals("COOLDOWN", s.kind());
        assertEquals(3, s.cooldownDays());
        assertTrue(s.buyLocked());
    }

    @Test
    @DisplayName("vendu, verrou d'achat => attente sous Down2")
    void lockedUntilDown2() {
        StrategyStateDto s = StrategyStateDescriber.describe(signal().buyLocked(true).build(), null, config());
        assertEquals("LOCKED_UNTIL_DOWN2", s.kind());
        assertEquals(80.0, s.down2());
        assertNull(s.cooldownDays());
    }

    @Test
    @DisplayName("moon actif => stop = pic × (1 − %) ; rien d'armé => surveillance")
    void moonAndWatching() {
        RainbowLiveEngineState e = RainbowLiveEngineState.builder().moonActive(true).moonPeak(500.0).build();
        StrategyStateDto moon = StrategyStateDescriber.describe(signal().moonMode(true).build(), e, config());
        assertEquals("MOON_ACTIVE", moon.kind());
        assertEquals(400.0, moon.triggerPrice(), 1e-9);
        StrategyStateDto idle = StrategyStateDescriber.describe(signal().build(), null, config());
        assertEquals("WATCHING", idle.kind());
        assertFalse(idle.buyLocked());
        assertEquals(3, idle.zone());
    }
}
