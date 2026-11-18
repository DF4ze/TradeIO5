package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape 5 : espace de paramètres L1 = configuration ; δ, domaine dur, entiers, gelés par construction, ordre des seuils ATR. */
class PresetSpaceTest {

    private static final double EPS = 1e-9;

    private static RainbowAtrParamSet[] preset(String asset) {
        return RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]);
    }

    @Test
    void freeParametersStayWithinRelativeDeltaAndHardDomain() {
        for (String asset : List.of("BTC", "ETH", "PAXG")) {
            RainbowAtrParamSet[] sets = preset(asset);
            PresetSpaceConfig cfg = PresetSpaceConfig.defaults();
            PresetParams.Built built = PresetParams.build(sets, cfg);
            Map<String, Double> inPlace = PresetParams.values(sets);
            for (PresetParams.ParamInfo p : built.info()) {
                if (p.frozen()) {
                    continue;
                }
                double v = inPlace.get(p.name());
                PresetParam param = PresetParam.byKey(p.name().substring(p.name().indexOf('.') + 1)).orElseThrow();
                assertEquals(v, p.inPlace(), EPS);
                assertTrue(p.min() >= v * (1 - cfg.defaultDelta()) - EPS, p.name() + " min " + p.min());
                assertTrue(p.max() <= v * (1 + cfg.defaultDelta()) + EPS, p.name() + " max " + p.max());
                assertTrue(p.min() >= param.domainMin() - EPS && p.max() <= param.domainMax() + EPS, p.name() + " hors domaine");
                assertTrue(p.min() <= v + EPS && v <= p.max() + EPS);
                if (param.integer()) {
                    assertEquals(Math.rint(p.min()), p.min(), EPS);
                    assertEquals(Math.rint(p.max()), p.max(), EPS);
                    assertEquals(Math.rint(p.step()), p.step(), EPS);
                }
            }
            // le point de départ de la recherche est exactement le preset en place
            Candidate start = built.space().startCandidate();
            start.values().forEach((name, value) -> assertEquals(inPlace.get(name), value, EPS, name));
        }
    }

    @Test
    void everyGridValueRespectsBoundsAndIntegers() {
        RainbowAtrParamSet[] sets = preset("ETH");
        PresetParams.Built built = PresetParams.build(sets, PresetSpaceConfig.defaults());
        for (ParamSpace.Param p : built.space().params()) {
            for (int k = 0; k < p.gridSize(); k++) {
                double value = p.snap(p.min() + k * p.step());
                assertTrue(value >= p.min() - EPS && value <= p.max() + EPS);
                if (p.kind() == ParamSpace.Kind.INTEGER) {
                    assertEquals(Math.rint(value), value, EPS);
                }
            }
        }
        // sellFraction reste dans ]0, 1]
        built.info().stream().filter(i -> !i.frozen() && i.name().endsWith("sellFraction")).forEach(i -> {
            assertTrue(i.min() > 0);
            assertTrue(i.max() <= 1.0 + EPS);
        });
    }

    @Test
    void deltaIsConfigurablePerParameter() {
        RainbowAtrParamSet[] sets = preset("ETH");
        PresetSpaceConfig narrow = PresetSpaceConfig.defaults().withDelta(0.10).with("trailingStopBuyPct", true, 0.5);
        PresetParams.Built built = PresetParams.build(sets, narrow);
        double v = sets[PresetOptimizerTestSupport.BEAR].tuning().trailingStopBuyPct();
        PresetParams.ParamInfo trailing = built.info().stream().filter(i -> i.name().equals("bear.trailingStopBuyPct")).findFirst().orElseThrow();
        assertTrue(trailing.max() > v * 1.25, "δ propre au paramètre (50 %) pris en compte");
        PresetParams.ParamInfo down2 = built.info().stream().filter(i -> i.name().equals("bear.atrMultDown2")).findFirst().orElseThrow();
        assertTrue(down2.max() <= sets[PresetOptimizerTestSupport.BEAR].tuning().atrMultDown2() * 1.10 + EPS);
        assertThrows(IllegalArgumentException.class, () -> PresetSpaceConfig.defaults().withDelta(1.5));
        assertThrows(IllegalArgumentException.class, () -> PresetSpaceConfig.defaults().with("smaPeriod", true, null));
    }

    @Test
    void parameterFrozenByConfigurationIsNeverInTheSpaceNorChanged() {
        RainbowAtrParamSet[] sets = preset("ETH");
        PresetSpaceConfig cfg = PresetSpaceConfig.defaults().with("cooldownDays", false, null).with("sellFraction", false, null);
        PresetParams.Built built = PresetParams.build(sets, cfg);
        assertTrue(built.space().params().stream().noneMatch(p -> p.name().endsWith("cooldownDays") || p.name().endsWith("sellFraction")));
        assertTrue(built.info().stream().filter(i -> i.name().endsWith("cooldownDays")).allMatch(PresetParams.ParamInfo::frozen));
        // appliquer un candidat extrême ne touche pas ce qui est gelé
        Map<String, Double> values = new LinkedHashMap<>();
        built.space().params().forEach(p -> values.put(p.name(), p.max()));
        RainbowAtrParamSet[] out = PresetParams.apply(sets, new Candidate(values));
        for (int i = 0; i < sets.length; i++) {
            RainbowAtrTuning before = sets[i].tuning();
            RainbowAtrTuning after = out[i].tuning();
            assertEquals(before.cooldownDays(), after.cooldownDays());
            assertEquals(before.sellFraction(), after.sellFraction(), EPS);
        }
    }

    @Test
    void presetPartsOutsideTheSpaceAreFrozenByConstruction() {
        RainbowAtrParamSet[] sets = preset("BTC");
        PresetParams.Built built = PresetParams.build(sets, PresetSpaceConfig.defaults());
        Map<String, Double> values = new LinkedHashMap<>();
        built.space().params().forEach(p -> values.put(p.name(), p.max()));
        RainbowAtrParamSet[] out = PresetParams.apply(sets, new Candidate(values));
        for (int i = 0; i < sets.length; i++) {
            RainbowAtrTuning b = sets[i].tuning();
            RainbowAtrTuning a = out[i].tuning();
            assertEquals(sets[i].name(), out[i].name());
            assertSame(sets[i].globals(), out[i].globals()); // ATH, Moon, multiplicateurs, baseAmount : jamais touchés
            assertEquals(b.smaPeriod(), a.smaPeriod());
            assertEquals(b.atrPeriod(), a.atrPeriod());
            assertEquals(b.buyReentryMode(), a.buyReentryMode());
            assertEquals(b.sellReentryMode(), a.sellReentryMode());
            assertEquals(b.allowSellDuringCooldown(), a.allowSellDuringCooldown());
            assertEquals(b.cooldownAfterSellOn(), a.cooldownAfterSellOn());
            assertEquals(b.blockBuyAfterSellUntilDown2(), a.blockBuyAfterSellUntilDown2());
        }
        assertTrue(PresetParam.FROZEN_BY_DESIGN.containsAll(List.of("smaPeriod", "atrPeriod", "ATH", "Moon", "baseAmount", "Trend")));
    }

    @Test
    void zeroValuedParametersStayFrozenWithAReason() {
        PresetParams.Built built = PresetParams.build(preset("BTC"), PresetSpaceConfig.defaults());
        PresetParams.ParamInfo zero = built.info().stream().filter(i -> i.name().equals("bear.atrMultDown1")).findFirst().orElseThrow();
        assertTrue(zero.frozen());
        assertNotNull(zero.reason());
        assertTrue(built.space().params().stream().noneMatch(p -> p.name().equals("bear.atrMultDown1")));
    }

    @Test
    void invertedAtrThresholdsAreRejectedByTheValidator() {
        RainbowAtrParamSet[] sets = preset("PAXG"); // bull : up1 = up2 = 2,0 tous deux libres
        var validator = PresetParams.validator(sets);
        Candidate start = PresetParams.build(sets, PresetSpaceConfig.defaults()).space().startCandidate();
        assertTrue(validator.apply(start).isEmpty(), "le preset en place est valide");
        Candidate inverted = start.with("bull.atrMultUp1", 2.5).with("bull.atrMultUp2", 1.5);
        List<String> reasons = validator.apply(inverted);
        assertFalse(reasons.isEmpty());
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("bull.atrMultUp2")));
    }
}
