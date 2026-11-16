package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Politiques de modulation : valeurs, bornes, validation")
class ModulationPoliciesTest {

    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);
    private static final ModulationSettings BASE = new ModulationSettings(20, 80, 1.0, 0.0);
    private static final double EPS = 1e-9;

    private static DayState state(Double exposure, Map<String, String> regimes, Map<String, Integer> days) {
        return new DayState(LocalDate.parse("2025-01-01"), 100, 100, 200, 200, 0, exposure, regimes, days);
    }

    private static ModulationPolicy policy(String type, Map<String, Double> params) {
        return ModulationPolicies.from(new PolicySpec(type, params));
    }

    private static Map<String, String> bear(String... assets) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String a : assets) {
            m.put(a, DayState.BEAR);
        }
        return m;
    }

    @Test
    @DisplayName("CONSTANT renvoie la base telle quelle")
    void constant() {
        assertSame(BASE, policy("CONSTANT", Map.of()).settingsFor(state(50.0, Map.of(), Map.of()), BASE));
    }

    @Test
    @DisplayName("REGIME_BAND : bande du régime majoritaire ; égalité, aucun lien ou RANGE => base")
    void regimeBand() {
        ModulationPolicy p = policy("REGIME_BAND", Map.of("bullMin", 20.0, "bullMax", 90.0, "bearMin", 0.0, "bearMax", 30.0));
        ModulationSettings bull = p.settingsFor(state(50.0, Map.of("BTC", DayState.BULL, "ETH", DayState.BULL, "PAXG", DayState.BEAR),
                Map.of()), BASE);
        assertEquals(20, bull.exposureMin(), EPS);
        assertEquals(90, bull.exposureMax(), EPS);
        assertEquals(BASE.power(), bull.power(), EPS);
        ModulationSettings bearSettings = p.settingsFor(state(50.0, bear("BTC"), Map.of()), BASE);
        assertEquals(0, bearSettings.exposureMin(), EPS);
        assertEquals(30, bearSettings.exposureMax(), EPS);
        assertSame(BASE, p.settingsFor(state(50.0, Map.of("BTC", DayState.BULL, "ETH", DayState.BEAR), Map.of()), BASE));
        assertSame(BASE, p.settingsFor(state(50.0, Map.of(), Map.of()), BASE));
        assertSame(BASE, p.settingsFor(state(50.0, Map.of("BTC", "RANGE"), Map.of()), BASE));
    }

    @Test
    @DisplayName("EXPOSURE_GAP_BIAS : gain x (cible - exposition) / 100, cible = milieu de bande ; inconnue => base")
    void exposureGapBias() {
        ModulationPolicy p = policy("EXPOSURE_GAP_BIAS", Map.of("gain", 1.0));
        assertEquals(0.2, p.settingsFor(state(30.0, Map.of(), Map.of()), BASE).bias(), EPS);
        assertEquals(-0.3, p.settingsFor(state(80.0, Map.of(), Map.of()), BASE).bias(), EPS);
        assertSame(BASE, p.settingsFor(state(null, Map.of(), Map.of()), BASE));
        ModulationPolicy strong = policy("EXPOSURE_GAP_BIAS", Map.of("gain", 10.0));
        assertEquals(1.0, strong.settingsFor(state(0.0, Map.of(), Map.of()), BASE).bias(), EPS);
    }

    @Test
    @DisplayName("BEAR_DERISK_RAMP : exposureMax décroît linéairement vers la cible sur rampDays, hors Bear => base")
    void bearDeriskRamp() {
        ModulationPolicy p = policy("BEAR_DERISK_RAMP", Map.of("bearTarget", 10.0, "rampDays", 30.0));
        assertEquals(80 - 70.0 * 15 / 30, p.settingsFor(state(50.0, bear("BTC"), Map.of("BTC", 15)), BASE).exposureMax(), EPS);
        assertEquals(10, p.settingsFor(state(50.0, bear("BTC"), Map.of("BTC", 30)), BASE).exposureMax(), EPS);
        assertEquals(10, p.settingsFor(state(50.0, bear("BTC"), Map.of("BTC", 90)), BASE).exposureMax(), EPS);
        assertSame(BASE, p.settingsFor(state(50.0, Map.of("BTC", DayState.BULL), Map.of("BTC", 90)), BASE));
        assertSame(BASE, policy("BEAR_DERISK_RAMP", Map.of("bearTarget", 90.0, "rampDays", 30.0))
                .settingsFor(state(50.0, bear("BTC"), Map.of("BTC", 90)), BASE));
    }

    @Test
    @DisplayName("Sortie bornée par les limites admin : bande élargie, biais et puissance clampés")
    void clampApplied() {
        ModulationSettings out = UserTradingSettingsService.clamp(new ModulationSettings(0, 4, 50.0, 0.99), LIMITS);
        assertTrue(out.exposureMax() - out.exposureMin() >= LIMITS.getMinBandWidthPts().doubleValue() - EPS);
        assertEquals(LIMITS.getBiasMax().doubleValue(), out.bias(), EPS);
        assertEquals(LIMITS.getPowerMax().doubleValue(), out.power(), EPS);
        ModulationSettings ramp = UserTradingSettingsService.clamp(policy("BEAR_DERISK_RAMP", Map.of("bearTarget", 0.0, "rampDays", 1.0))
                .settingsFor(state(50.0, bear("BTC"), Map.of("BTC", 5)), BASE), LIMITS);
        assertTrue(ramp.exposureMax() - ramp.exposureMin() >= LIMITS.getMinBandWidthPts().doubleValue() - EPS);
    }

    @Test
    @DisplayName("Validation : type inconnu, paramètre manquant, inconnu ou hors bornes")
    void validation() {
        assertThrows(ExpertValidationException.class, () -> policy("NOPE", Map.of()));
        assertThrows(ExpertValidationException.class, () -> ModulationPolicies.from(null));
        assertThrows(ExpertValidationException.class, () -> policy("CONSTANT", Map.of("x", 1.0)));
        ExpertValidationException missing = assertThrows(ExpertValidationException.class,
                () -> policy("REGIME_BAND", Map.of("bullMin", 20.0, "bullMax", 90.0)));
        assertTrue(missing.getFields().containsKey("policy.params.bearMin"));
        assertTrue(missing.getFields().containsKey("policy.params.bearMax"));
        assertTrue(assertThrows(ExpertValidationException.class,
                () -> policy("REGIME_BAND", Map.of("bullMin", 20.0, "bullMax", 120.0, "bearMin", 30.0, "bearMax", 10.0)))
                .getFields().keySet().containsAll(java.util.List.of("policy.params.bullMax", "policy.params.bearMax")));
        assertThrows(ExpertValidationException.class, () -> policy("EXPOSURE_GAP_BIAS", Map.of("gain", 0.0)));
        assertThrows(ExpertValidationException.class, () -> policy("EXPOSURE_GAP_BIAS", Map.of("gain", Double.NaN)));
        assertThrows(ExpertValidationException.class, () -> policy("BEAR_DERISK_RAMP", Map.of("bearTarget", 10.0, "rampDays", 0.0)));
        assertThrows(ExpertValidationException.class, () -> policy("BEAR_DERISK_RAMP", Map.of("bearTarget", 10.0, "rampDays", 2.5)));
        assertThrows(ExpertValidationException.class, () -> policy("BEAR_DERISK_RAMP", Map.of("bearTarget", -1.0, "rampDays", 30.0)));
    }
}
