package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.expert.ExpertPresetValidator;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Change;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Compare;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Rotation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape B : blocs, plateau, garde-fou, jeu inchangé, bloc de test hors réglage, reproductibilité, verdict. */
class BlockTuningTest {

    private static ScorerInputs inputs;
    private static ExecutorService pool;
    private static final LocalDate END = PresetOptimizerTestSupport.END;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), 10_000.0, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void close() {
        pool.shutdown();
    }

    /** Configuration de test : une passe, pas d'élargissement, affinage court (suite rapide). */
    private static TuneConfig fast() {
        return TuneConfig.defaults(END).withMaxPasses(1).withMaxWidenSteps(0).withRefinePoints(3);
    }

    private static final List<String> BULL = List.of("cooldownAfterSellOn", "allowSellDuringCooldown");
    private static final List<String> BEAR = List.of("trailingStopBuyPct");

    @Test
    @DisplayName("Les 4 blocs de 24 mois ne se chevauchent pas : contigus, plus récent finissant à la date demandée")
    void blocksDoNotOverlap() {
        List<Block> blocks = TuneBlocks.build(LocalDate.parse("2026-11-17"), 24, 4);
        assertEquals(4, blocks.size());
        assertEquals(LocalDate.parse("2026-11-17"), blocks.getFirst().to());
        assertEquals(LocalDate.parse("2020-11-17"), blocks.getLast().to());
        for (int i = 1; i < blocks.size(); i++) {
            Block recent = blocks.get(i - 1);
            Block older = blocks.get(i);
            assertTrue(older.to().isBefore(recent.from()), "chevauchement entre " + older + " et " + recent);
            assertEquals(recent.from().minusDays(1), older.to());
        }
        for (Block b : blocks) {
            assertEquals(b.to(), b.from().plusMonths(24).minusDays(1));
        }
    }

    @Test
    @DisplayName("Plateau : moyenne des valeurs ≥ 90 % du meilleur, pas le pic ; un trou ou un NaN coupe le plateau ; meilleur négatif géré")
    void plateauIsChosenNotPeak() {
        List<Double> v = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
        TunePlateau.Result r = TunePlateau.pick(v, List.of(10.0, 95.0, 100.0, 96.0, 20.0, 5.0), 0.9);
        assertEquals(3, r.size());
        assertEquals(3.0, r.value(), 1e-9);
        assertEquals(2, r.bestIndex());
        TunePlateau.Result skewed = TunePlateau.pick(v, List.of(100.0, 98.0, 95.0, 10.0, 5.0, 1.0), 0.9);
        assertEquals(2.0, skewed.value(), 1e-9);
        assertNotEquals(1.0, skewed.value());
        TunePlateau.Result cut = TunePlateau.pick(v, List.of(1.0, Double.NaN, 100.0, 100.0, 1.0, 1.0), 0.9);
        assertEquals(3.5, cut.value(), 1e-9);
        TunePlateau.Result negative = TunePlateau.pick(List.of(1.0, 2.0, 3.0), List.of(-10.0, -10.5, -30.0), 0.9);
        assertEquals(1.5, negative.value(), 1e-9);
        assertNull(TunePlateau.pick(List.of(1.0), List.of(Double.NaN), 0.9));
    }

    @Test
    @DisplayName("EXPOSURE_GUARD : au-delà de 1,25 × l'exposition du preset en place, rejeté ; la borne elle-même passe")
    void guardRejects() {
        assertTrue(BlockTuner.rejects(8.76, 7.0, 1.25));
        assertFalse(BlockTuner.rejects(8.75, 7.0, 1.25));
        assertFalse(BlockTuner.rejects(5.0, 7.0, 1.25));
    }

    @Test
    @DisplayName("Garde-fou dans la descente : facteur 0 ⇒ tout candidat qui expose est rejeté, aucun réglage retenu, preset en place intact")
    void guardRejectsInTuner() {
        TuneConfig cfg = fast().withExposureGuardFactor(0.0);
        BlockTuning.Plan plan = BlockTuning.plan(inputs, cfg);
        List<SensitivityEvaluator> tune = plan.validIndexes().stream().skip(1).map(plan.evaluators()::get).toList();
        RainbowAtrParamSet[] preset = plan.evaluators().getFirst().template().sets();
        BlockTuner.Result res = new BlockTuner(cfg, null).tune(tune, preset, List.of("sellFraction", "cooldownAfterSellOn"), BEAR);
        assertTrue(res.decisions().stream().noneMatch(BlockTuner.Decision::kept));
        assertTrue(res.guardRejections() > 0);
        assertEquals(List.of(preset), res.sets());
    }

    @Test
    @DisplayName("Un jeu inchangé reproduit le preset en place à l'identique (aucun réglage balayé, ou seuil de gain inatteignable)")
    void unchangedSetReproducesPreset() {
        TuneConfig cfg = fast();
        BlockTuning.Plan plan = BlockTuning.plan(inputs, cfg);
        List<SensitivityEvaluator> tune = plan.validIndexes().stream().skip(1).map(plan.evaluators()::get).toList();
        RainbowAtrParamSet[] preset = plan.evaluators().getFirst().template().sets();
        BlockTuner.Result none = new BlockTuner(cfg, null).tune(tune, preset, List.of(), List.of());
        assertEquals(List.of(preset), none.sets());
        BlockTuner.Result stiff = new BlockTuner(cfg.withMinGainPoints(1e6), null).tune(tune, preset, BULL, BEAR);
        assertEquals(List.of(preset), stiff.sets());
        assertTrue(stiff.decisions().stream().noneMatch(BlockTuner.Decision::kept));
        for (int i : plan.validIndexes()) {
            assertEquals(plan.presetReadings()[i], plan.evaluators().get(i).cycle(stiff.asArray(), false));
        }
    }

    @Test
    @DisplayName("Le bloc de test n'est jamais rejoué pendant le réglage ; il n'apparaît dans aucune liste de réglage")
    void testBlockNeverInTuning() {
        TuneConfig cfg = fast();
        BlockTuning.Plan plan = BlockTuning.plan(inputs, cfg);
        SensitivityEvaluator testEv = plan.evaluators().get(plan.validIndexes().getFirst());
        long before = testEv.replays();
        List<SensitivityEvaluator> tune = plan.validIndexes().stream().skip(1).map(plan.evaluators()::get).toList();
        assertFalse(tune.contains(testEv));
        new BlockTuner(cfg, null).tune(tune, testEv.template().sets(), BULL, BEAR);
        assertEquals(before, testEv.replays());
        TuneReport report = BlockTuning.run(inputs, cfg, null, BULL, BEAR);
        for (Rotation r : report.rotations()) {
            assertFalse(r.tuneBlocks().contains(r.testBlock()));
            assertEquals(report.rotations().size() - 1, r.tuneBlocks().size());
            assertEquals(r.testBlock(), r.test().block());
        }
        assertEquals(report.rotations().size(), report.rotations().stream().map(Rotation::testBlock).distinct().count());
    }

    @Test
    @DisplayName("Reproductible : mêmes entrées ⇒ même rapport (hors durées), en série comme avec un pool ; exports au format de l'Expert, valides")
    void reproducibleAndExportable() {
        TuneConfig cfg = fast();
        TuneReport a = BlockTuning.run(inputs, cfg, null, BULL, BEAR);
        TuneReport b = BlockTuning.run(inputs, cfg, null, BULL, BEAR);
        TuneReport c = BlockTuning.run(inputs, cfg, pool, BULL, BEAR);
        assertEquals(a.toJsonWithoutTimings(), b.toJsonWithoutTimings());
        assertEquals(a.toJsonWithoutTimings(), c.toJsonWithoutTimings());
        assertEquals(4, a.rotations().size());
        assertTrue(a.multiplicity().replays() > 0 && a.multiplicity().valuesTested() > 0);
        assertTrue(a.toMarkdown().contains("Verdict"));
        ExpertPresetValidator validator = new ExpertPresetValidator();
        for (var export : List.of(a.challenger().bullExport(), a.challenger().bearExport())) {
            assertTrue(export.get("name").toString().length() <= ExpertPresetValidator.NAME_MAX);
            validator.rainbow((Integer) export.get("analysisWindowMonths"), (RainbowAtrTuning) export.get("tuning"), (RainbowAtrGlobals) export.get("globals"));
        }
        assertTrue(TuneReport.exportJson(a.challenger().bullExport()).contains("\"tuning\""));
    }

    @Test
    @DisplayName("Estimation : croît avec les blocs et les passes, en minutes de rejeux × durée")
    void estimateScales() {
        TuneReport.Estimate four = BlockTuning.estimate(TuneConfig.defaults(END), 4);
        TuneReport.Estimate three = BlockTuning.estimate(TuneConfig.defaults(END), 3);
        TuneReport.Estimate onePass = BlockTuning.estimate(TuneConfig.defaults(END).withMaxPasses(1), 4);
        assertTrue(four.worstCaseReplays() > three.worstCaseReplays());
        assertTrue(four.worstCaseReplays() > onePass.worstCaseReplays());
        assertEquals(four.worstCaseReplays() * 25.0 / 60_000.0, four.sequentialMinutes(), 1e-9);
    }

    // ------------------------------------------------------------------------------------------------ verdict (rotations synthétiques)

    private static Reading reading(double pnlPct, double fixedPnl, double exposure) {
        return new Reading(END, END, 0, 0, 0, pnlPct, fixedPnl, 0, 0, 0, null, 0, exposure, 0, 0);
    }

    private static Rotation rotation(int test, double dPreset, double dDca, boolean expoOk, List<Change> changes) {
        Compare c = new Compare(test, reading(0, 0, 7), reading(dPreset, 0, 7), 0, dPreset, dPreset - dDca, dPreset, dDca, expoOk);
        boolean bp = dPreset > 0;
        boolean bd = dDca > 0;
        return new Rotation(test, List.of(), changes, List.of(), 1, c, bp, bd, bp && bd && expoOk, 0, 0);
    }

    @Test
    @DisplayName("Verdict : ADOPTABLE à 3 rotations gagnantes sur 4 avec médianes > 0 et exposition dans la borne ; NUL sinon")
    void verdict() {
        TuneConfig cfg = TuneConfig.defaults(END);
        List<Rotation> good = List.of(rotation(0, 1, 1, true, List.of()), rotation(1, 2, 2, true, List.of()),
                rotation(2, 0.5, 0.5, true, List.of()), rotation(3, -1, -1, true, List.of()));
        TuneReport.Aggregate ok = BlockTuning.aggregate(good, cfg, BULL, BEAR);
        assertEquals("ADOPTABLE", ok.verdict());
        assertEquals(3, ok.beatsBoth());
        List<Rotation> two = List.of(rotation(0, 1, 1, true, List.of()), rotation(1, 2, 2, true, List.of()), rotation(2, -0.5, -0.5, true, List.of()),
                rotation(3, -1, -1, true, List.of()));
        assertEquals("NUL", BlockTuning.aggregate(two, cfg, BULL, BEAR).verdict());
        List<Rotation> expo = new ArrayList<>(good);
        expo.set(0, rotation(0, 1, 1, false, List.of()));
        assertEquals("NUL", BlockTuning.aggregate(expo, cfg, BULL, BEAR).verdict());
        List<Rotation> dcaFails = List.of(rotation(0, 1, -1, true, List.of()), rotation(1, 2, -2, true, List.of()), rotation(2, 3, 3, true, List.of()),
                rotation(3, 1, 1, true, List.of()));
        assertEquals("NUL", BlockTuning.aggregate(dcaFails, cfg, BULL, BEAR).verdict());
        assertEquals("NON_EVALUABLE", BlockTuning.aggregate(good.subList(0, 3), cfg, BULL, BEAR).verdict());
    }

    @Test
    @DisplayName("Inversions : sens opposé d'une rotation à l'autre, ou réglage retenu dans certaines rotations seulement")
    void inversions() {
        List<Rotation> rs = List.of(
                rotation(0, 0, 0, true, List.of(new Change("BULL", "sellFraction", "0.2", "0.5"), new Change("BULL", "cooldownAfterSellOn", "true", "false"))),
                rotation(1, 0, 0, true, List.of(new Change("BULL", "sellFraction", "0.2", "0.05"))),
                rotation(2, 0, 0, true, List.of()), rotation(3, 0, 0, true, List.of()));
        List<TuneReport.Inversion> inv = BlockTuning.inversions(rs, List.of("sellFraction", "cooldownAfterSellOn"), BEAR);
        assertTrue(inv.stream().anyMatch(i -> i.key().equals("sellFraction") && i.kind().equals("sens opposé")));
        assertTrue(inv.stream().anyMatch(i -> i.key().equals("cooldownAfterSellOn") && i.kind().startsWith("retenu dans 1/4")));
        assertEquals(2, inv.size());
    }
}
