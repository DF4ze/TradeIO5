package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.AssetPolicy;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.Pick;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.ProfileResult;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyReport.Rotation;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyStudy.Combo;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyStudy.ConstantPick;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyStudy.PolicyPick;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Étape C2 : bornes hautes = base ⇒ constante exacte ; bloc de test jamais dans le réglage ; plafond appliqué aux blocs de réglage seulement ;
 * ×1 = chiffres de C1 / B' ; règle de verdict ; reproductibilité.
 */
class ExposurePolicyTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final double CASH = 10_000.0;
    private static ScorerInputs inputs;
    private static ExposurePolicyConfig small;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), CASH, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        ExposurePolicyConfig d = ExposurePolicyConfig.defaults(END);
        small = new ExposurePolicyConfig(d.parity(), List.of(0.5, 1.0, 2.0, 4.0), List.of(0.125, 0.25), List.of(30.0, 100.0), 3, 3);
    }

    private static Line line(double gain, double dd) {
        return new Line(0, gain, 10.0, dd, 1, 0);
    }

    private static Combo combo(double bull, double bear, double mult, Line... lines) {
        return new Combo(bull, bear, mult, lines);
    }

    @Test
    @DisplayName("Bornes hautes = valeurs de base (100 / 100) : la politique redonne exactement la constante, bloc par bloc et historique complet")
    void defaultBoundsEqualConstant() {
        double top = inputs.baseSettings().exposureMax();
        List<Block> blocks = TuneBlocks.build(END, 24, 4);
        for (double mult : new double[]{1.0, 3.0}) {
            for (Block b : blocks) {
                SensitivityEvaluator ev = new SensitivityEvaluator(inputs.withEndDate(b.to()), 24, b.from());
                RainbowAtrParamSet[] sets = ev.template().sets();
                double ref = ev.referenceBase(sets);
                Line c = ExposurePolicyStudy.constantLine(ev, sets, false, mult, ref);
                Line p = ExposurePolicyStudy.policyLine(ev, sets, false, mult, ref, top, top);
                assertEquals(c, p, "bloc " + b.label() + " ×" + mult);
            }
        }
        SensitivityEvaluator full = new SensitivityEvaluator(inputs.withEndDate(END), 24);
        RainbowAtrParamSet[] sets = full.template().sets();
        double ref = full.referenceBase(sets);
        assertEquals(ExposurePolicyStudy.constantLine(full, sets, true, 2.0, ref), ExposurePolicyStudy.policyLine(full, sets, true, 2.0, ref, top, top));
    }

    @Test
    @DisplayName("Une borne haute basse change réellement la mesure (la politique n'est pas un no-op)")
    void lowerBoundChangesResult() {
        Block b = TuneBlocks.build(END, 24, 4).getFirst();
        SensitivityEvaluator ev = new SensitivityEvaluator(inputs.withEndDate(b.to()), 24, b.from());
        RainbowAtrParamSet[] sets = ev.template().sets();
        double ref = ev.referenceBase(sets);
        Line c = ExposurePolicyStudy.constantLine(ev, sets, false, 6.0, ref);
        Line p = ExposurePolicyStudy.policyLine(ev, sets, false, 6.0, ref, 20.0, 20.0);
        assertTrue(p.avgExposurePct() < c.avgExposurePct(), "une borne haute de 20 % doit baisser l'exposition moyenne");
    }

    @Test
    @DisplayName("Multiplicateur ×1 = chiffres de C1 / B' (preset du bloc, bloc par bloc)")
    void multiplierOneEqualsC1() {
        ParityReport.AssetReport b = ExposureParity.run(inputs, small.parity(), null);
        List<Block> blocks = TuneBlocks.build(END, 24, 4);
        for (int i = 0; i < blocks.size(); i++) {
            if (!b.blocks().get(i).valid()) {
                continue;
            }
            SensitivityEvaluator ev = new SensitivityEvaluator(inputs.withEndDate(blocks.get(i).to()), 24, blocks.get(i).from());
            RainbowAtrParamSet[] sets = ev.template().sets();
            Line c = ExposurePolicyStudy.constantLine(ev, sets, false, 1.0, ev.referenceBase(sets));
            assertEquals(b.blocks().get(i).preset(), ExposureParity.metrics(c));
        }
    }

    @Test
    @DisplayName("Le bloc de test n'entre jamais dans le réglage : la sélection ne lit que les blocs de réglage (blocs de test absents = NPE si lus)")
    void testBlockNeverTouchedByTuning() {
        // lignes {0, 1, 2} = réglage, 3 = test (null : toute lecture lèverait)
        Combo a = combo(100, 100, 1, line(10, -10), line(10, -10), line(10, -10), null);
        Combo b = combo(50, 100, 2, line(20, -20), line(20, -20), line(20, -20), null);
        PolicyPick pick = ExposurePolicyStudy.selectPolicy(List.of(a, b), List.of(0, 1, 2), -30.0);
        assertEquals(b, pick.combo());
        assertEquals(20.0, pick.medianGain(), 1e-12);
        // les rotations d'un run réel : le test n'est jamais dans ses blocs de réglage
        AssetPolicy r = ExposurePolicyStudy.run(inputs, small, null);
        for (ProfileResult p : r.profiles()) {
            for (Rotation rot : p.rotations()) {
                assertFalse(rot.tuneBlocks().contains(rot.testBlock()));
                assertEquals(3, rot.tuneBlocks().size());
            }
            assertEquals(4, p.rotations().stream().map(Rotation::testBlock).distinct().count());
        }
    }

    @Test
    @DisplayName("Plafond appliqué aux blocs de réglage seulement : un dépassement sur le bloc de test ne change pas le réglage")
    void capAppliedOnTuningBlocksOnly() {
        // meilleur gain, plafond tenu sur 0-2, dépassé sur le bloc de test 3 : retenu quand même
        Combo greedy = combo(100, 100, 4, line(50, -28), line(50, -29), line(50, -27), line(50, -55));
        Combo safe = combo(100, 100, 1, line(10, -5), line(10, -5), line(10, -5), line(10, -5));
        PolicyPick p = ExposurePolicyStudy.selectPolicy(List.of(safe, greedy), List.of(0, 1, 2), -30.0);
        assertEquals(greedy, p.combo());
        assertEquals("REGLE", p.status());
        assertEquals(-29.0, p.worstDrawdown(), 1e-12);
        assertFalse(ExposurePolicyStudy.holds(greedy.lines()[3], -30.0), "le dépassement du bloc de test est rapporté, pas corrigé");
        // un dépassement sur un bloc de réglage rejette la combinaison
        Combo breach = combo(100, 100, 8, line(90, -31), line(90, -10), line(90, -10), line(90, -10));
        assertEquals(greedy, ExposurePolicyStudy.selectPolicy(List.of(safe, greedy, breach), List.of(0, 1, 2), -30.0).combo());
        // plafond intenable sur les blocs de réglage : combinaison la moins profonde, signalée
        PolicyPick none = ExposurePolicyStudy.selectPolicy(List.of(greedy, breach), List.of(0, 1, 2), -20.0);
        assertEquals("PLAFOND_NON_TENU_REGLAGE", none.status());
        assertEquals(greedy, none.combo());
    }

    @Test
    @DisplayName("Constante : premier franchissement du plafond interpolé en pire drawdown ; statuts aux bords ; bloc hors réglage ignoré")
    void constantMultiplierInterpolation() {
        List<Double> m = List.of(1.0, 2.0, 4.0);
        ConstantPick mid = ExposurePolicyStudy.constantMultiplier(m, new double[]{-10, -20, -40}, -30.0);
        assertEquals("INTERPOLE", mid.status());
        assertEquals(3.0, mid.multiplier(), 1e-12);
        assertEquals("SOUS_LE_MINIMUM", ExposurePolicyStudy.constantMultiplier(m, new double[]{-35, -40, -50}, -30.0).status());
        ConstantPick top = ExposurePolicyStudy.constantMultiplier(m, new double[]{-5, -6, -7}, -30.0);
        assertEquals("PLAFOND_NON_ATTEINT", top.status());
        assertEquals(4.0, top.multiplier(), 1e-12);
    }

    @Test
    @DisplayName("Règle de verdict : ≥ 3 rotations gagnées, médiane Δ > 0, plafond au moins aussi bien tenu ; sinon NUL avec la raison")
    void verdictRule() {
        assertEquals("POLITIQUE_UTILE", verdictOf(new double[]{2, 3, 1, -1}, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true}).verdict());
        ProfileResult gain = verdictOf(new double[]{2, 3, -1, -1}, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true});
        assertEquals("NUL", gain.verdict());
        assertTrue(gain.reason().startsWith("gain"));
        ProfileResult median = verdictOf(new double[]{0.1, 0.1, 0.1, -9}, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true});
        assertEquals("POLITIQUE_UTILE", median.verdict());
        ProfileResult medianNeg = verdictOf(new double[]{0.0, 5, 5, 5}, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true});
        assertEquals("POLITIQUE_UTILE", medianNeg.verdict());
        // plafond : la politique dépasse là où la constante tient
        ProfileResult cap = verdictOf(new double[]{2, 3, 1, 4}, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, false});
        assertEquals("NUL", cap.verdict());
        assertTrue(cap.capFail());
        assertTrue(cap.reason().contains("plafond"));
        // la constante dépasse aussi : pas de reproche de plafond à la politique
        ProfileResult both = verdictOf(new double[]{2, 3, 1, 4}, new boolean[]{true, true, true, false}, new boolean[]{true, true, true, false});
        assertEquals("POLITIQUE_UTILE", both.verdict());
        assertEquals(1, both.policyBreaches());
        assertEquals(1, both.constantBreaches());
    }

    @Test
    @DisplayName("Stabilité : mêmes bornes dans ≥ 3 rotations sur 4 ; rapportée sans modifier la règle")
    void stabilityReported() {
        ProfileResult stable = verdictOf(new double[]{1, 1, 1, 1}, new boolean[]{true, true, true, true}, new boolean[]{true, true, true, true});
        assertTrue(stable.stable());
        assertEquals(4, stable.modalCount());
    }

    private static ProfileResult verdictOf(double[] deltas, boolean[] constantHolds, boolean[] policyHolds) {
        List<Rotation> rots = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Pick c = new Pick(null, null, 1.0, "INTERPOLE", null, null, line(10, -10), constantHolds[i]);
            Pick p = new Pick(100.0, 30.0, 1.0, "REGLE", 1.0, -10.0, line(10 + deltas[i], -10), policyHolds[i]);
            rots.add(new Rotation(i, "b" + i, List.of(), c, p, deltas[i]));
        }
        return ExposurePolicyStudy.verdict("BALANCED", -25.0, rots, null, small);
    }

    @Test
    @DisplayName("Rapport : plafonds lus dans ModulationDefaults, mesure de test = rejeu direct au réglage retenu, multiplicité cohérente")
    void reportConsistent() {
        AssetPolicy r = ExposurePolicyStudy.run(inputs, small, null);
        assertEquals(4, r.profiles().size());
        var caps = FrontierConfig.profileCaps();
        List<Block> blocks = TuneBlocks.build(END, 24, 4);
        for (ProfileResult p : r.profiles()) {
            assertEquals(caps.get(StartingProfile.valueOf(p.profile())), p.capPct(), 1e-12);
            for (Rotation rot : p.rotations()) {
                Block tb = blocks.get(rot.testBlock());
                SensitivityEvaluator ev = new SensitivityEvaluator(inputs.withEndDate(tb.to()), 24, tb.from());
                RainbowAtrParamSet[] sets = ev.template().sets();
                double ref = ev.referenceBase(sets);
                Pick c = rot.constant();
                Pick pol = rot.policy();
                assertEquals(ExposurePolicyStudy.constantLine(ev, sets, false, c.multiplier(), ref), c.test());
                assertEquals(ExposurePolicyStudy.policyLine(ev, sets, false, pol.multiplier(), ref, pol.bullMax(), pol.bearMax()), pol.test());
                assertEquals(pol.test().pnlPct() - c.test().pnlPct(), rot.deltaGain(), 1e-12);
            }
            assertNotNull(p.full());
        }
        assertEquals(small.combos(), r.multiplicity().policyCombos());
        assertEquals(small.constantGrid().size(), r.multiplicity().constantGridSize());
        assertTrue(r.multiplicity().replays() > 0);
    }

    @Test
    @DisplayName("Reproductibilité : série et pool de 3 donnent le même JSON (hors durées)")
    void reproducible() {
        AssetPolicy a = ExposurePolicyStudy.run(inputs, small, null);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            AssetPolicy b = ExposurePolicyStudy.run(inputs, small, pool);
            assertEquals(new ExposurePolicyReport("d", small, List.of(a), List.of()).toJsonWithoutTimings(),
                    new ExposurePolicyReport("d", small, List.of(b), List.of()).toJsonWithoutTimings());
        } finally {
            pool.shutdown();
        }
    }
}
