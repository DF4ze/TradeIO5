package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ModulationDefaults")
class ModulationDefaultsTest {

    @Test
    @DisplayName("Chaque défaut est dans ses bornes absolues, bornes cohérentes")
    void defaultsWithinBounds() {
        assertEquals(8, ModulationDefaults.LIMITS.size());
        ModulationDefaults.LIMITS.forEach((name, limit) -> {
            assertTrue(limit.min().compareTo(limit.max()) <= 0, name);
            assertTrue(limit.contains(limit.defaultValue()), name);
        });
    }

    @Test
    @DisplayName("Défauts validés par Clem")
    void agreedDefaults() {
        RainbowModulationControl c = RainbowModulationControl.defaults(Instant.EPOCH, null);
        assertEquals(0, new BigDecimal("25").compareTo(c.getRegulationMarginPct()));
        assertEquals(0, new BigDecimal("2").compareTo(c.getSellBoostMax()));
        assertEquals(0, new BigDecimal("0.5").compareTo(c.getBiasMax()));
        assertEquals(0, new BigDecimal("0.25").compareTo(c.getPowerMin()));
        assertEquals(0, new BigDecimal("2").compareTo(c.getPowerMax()));
        assertEquals(0, new BigDecimal("10").compareTo(c.getMinBandWidthPts()));
        assertEquals(0, new BigDecimal("2.5").compareTo(c.getModulationProductMax()));
        assertEquals(90, c.getAutonomyDays());
    }

    @Test
    @DisplayName("Limit.contains : bornes incluses")
    void containsIsInclusive() {
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.REGULATION_MARGIN_PCT);
        assertTrue(limit.contains(new BigDecimal("5")));
        assertTrue(limit.contains(new BigDecimal("50")));
        assertTrue(!limit.contains(new BigDecimal("4.99")));
        assertTrue(!limit.contains(new BigDecimal("50.01")));
    }
}
