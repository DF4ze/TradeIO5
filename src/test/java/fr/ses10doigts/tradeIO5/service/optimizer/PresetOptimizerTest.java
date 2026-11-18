package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.AssetReport;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.FoldResult;
import fr.ses10doigts.tradeIO5.service.optimizer.PresetVerdict.FoldScore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.END;
import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.LIMITS;
import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.csv;
import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.link;
import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.oneFold;
import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.smallSpace;
import static fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizerTestSupport.wallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape 5 : veto d'exposition, reproductibilité, NON_OPTIMISABLE, GARDER_PRESET, règle de verdict, cadences et budget. Données : CSV D1 du repo. */
class PresetOptimizerTest {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    private static RainbowAtrParamSet[] preset(String asset) {
        return RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]);
    }

    private static PresetOptimizer optimizer() {
        return new PresetOptimizer(POOL, smallSpace());
    }

    // ---------------------------------------------------------------- veto d'exposition

    @Test
    void exposureAboveTheProfileBandIsVetoed() throws IOException {
        ScorerInputs in = ScorerInputs.of(List.of(link("BTC", csv("BTC", Integer.MAX_VALUE))), 10_000.0, END, StartingProfile.CUSTOM, LIMITS);
        ResolvedCandidate resolved = new ResolvedCandidate(null, new AthCap(6.0, 2.0), 1.0);
        ScoreReport tight = PresetScoring.score(in, preset("BTC"), resolved, CostMode.SIMPLIFIED, false, 0.0);
        assertTrue(tight.vetoed());
        assertTrue(tight.vetoes().contains(OptimizerDefaults.VETO_EXPOSURE));
        assertEquals(OptimizerDefaults.VETO_SCORE, tight.score());
        ScoreReport wide = PresetScoring.score(in, preset("BTC"), resolved, CostMode.SIMPLIFIED, false, 100.0);
        assertFalse(wide.vetoes().contains(OptimizerDefaults.VETO_EXPOSURE));
    }

    @Test
    void invalidCandidateIsRejectedWithoutReplayAndCounted() throws IOException {
        RainbowAtrParamSet[] sets = preset("PAXG");
        ScorerInputs in = ScorerInputs.of(List.of(link("PAXG", csv("PAXG", Integer.MAX_VALUE))), 10_000.0, END, StartingProfile.CUSTOM, LIMITS);
        PresetObjective objective = new PresetObjective(in, sets, new ResolvedCandidate(null, null, 1.0), PresetParams.validator(sets), 100.0,
                List.of());
        Candidate start = PresetParams.build(sets, PresetSpaceConfig.defaults()).space().startCandidate();
        Evaluation e = objective.evaluate(start.with("bull.atrMultUp1", 2.5).with("bull.atrMultUp2", 1.5), Fidelity.SHORT_WINDOW_SIMPLIFIED, Scenario.BASE);
        assertTrue(e.vetoed());
        assertEquals(List.of(OptimizerDefaults.VETO_INVALID), e.vetoes());
    }

    // ---------------------------------------------------------------- walk-forward

    @Test
    void sameSeedGivesTheSameReportAndCandlesAreFingerprinted() throws IOException {
        List<MarketData> btc = csv("BTC", Integer.MAX_VALUE);
        PresetOptimizationReport a = optimizer().run(wallet(List.of(link("BTC", btc)), LIMITS), oneFold(), SearchControl.none());
        PresetOptimizationReport b = optimizer().run(wallet(List.of(link("BTC", btc)), LIMITS), oneFold(), SearchControl.none());
        assertEquals(a.toJsonWithoutTimings(), b.toJsonWithoutTimings());
        AssetReport btcReport = a.assets().getFirst();
        assertEquals(OptimizerDefaults.STATUS_OPTIMIZABLE, btcReport.status());
        FoldResult fold = btcReport.folds().getFirst();
        assertFalse(fold.trainFingerprints().isEmpty());
        assertFalse(fold.trainFingerprints().getFirst().lastDay().isAfter(fold.trainEnd()));
        assertFalse(fold.testFingerprints().getFirst().lastDay().isAfter(fold.testEnd()));
        assertTrue(a.multiplicity().searchTrials() > 0);
        assertEquals(1, a.multiplicity().searches());
    }

    @Test
    void challengerStaysInsideTheDeltaAroundThePresetAndNothingFrozenMoves() throws IOException {
        PresetOptimizationReport r = optimizer().run(wallet(List.of(link("BTC", csv("BTC", Integer.MAX_VALUE))), LIMITS), oneFold(), SearchControl.none());
        AssetReport a = r.assets().getFirst();
        FoldResult fold = a.folds().getFirst();
        if (fold.challenger() != null) {
            fold.challenger().forEach((name, value) -> {
                double inPlace = a.inPlace().get(name);
                assertTrue(Math.abs(value - inPlace) <= Math.abs(inPlace) * r.inputs().space().defaultDelta() + 1e-9, name);
            });
            a.params().stream().filter(PresetParams.ParamInfo::frozen).forEach(p -> assertFalse(fold.challenger().containsKey(p.name())));
        }
        assertTrue(a.params().stream().filter(p -> !p.frozen()).allMatch(p -> p.name().endsWith("atrMultDown2") || p.name().endsWith("sellFraction")));
    }

    @Test
    void insufficientHistoryIsReportedNotSilentlyExcluded() throws IOException {
        PresetOptimizationReport r = optimizer().run(wallet(List.of(link("PAXG", csv("PAXG", Integer.MAX_VALUE))), LIMITS),
                oneFold().withFolds(List.of(LocalDate.parse("2021-12-31")), 6), SearchControl.none()); // ~490 bougies avant la fin d'entraînement
        AssetReport a = r.assets().getFirst();
        assertEquals(OptimizerDefaults.STATUS_NOT_OPTIMIZABLE, a.status());
        assertEquals(OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY, a.statusReason());
        assertFalse(a.history().isEmpty());
        assertTrue(a.history().getFirst().candlesAvailable() < a.history().getFirst().candlesRequired());
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET, a.verdict().decision());
        assertEquals(0, r.multiplicity().searches());
        assertTrue(a.folds().stream().noneMatch(FoldResult::usable));
    }

    @Test
    void everythingVetoedKeepsThePresetAndNeverRetainsACandidate() throws IOException {
        RainbowModulationControl strict = RainbowModulationControl.defaults(Instant.EPOCH, null);
        strict.setDrawdownCapBalancedPct(new BigDecimal("-0.0001")); // le moindre drawdown est un veto : tous les candidats et le preset
        PresetOptimizationReport r = optimizer().run(wallet(List.of(link("BTC", csv("BTC", Integer.MAX_VALUE))), strict), oneFold(), SearchControl.none());
        AssetReport a = r.assets().getFirst();
        FoldResult fold = a.folds().getFirst();
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET, fold.foldDecision());
        assertEquals(OptimizerDefaults.FOLD_TRAIN_VETOED, fold.foldReason());
        assertNull(fold.challenger());
        assertFalse(fold.won());
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET, a.verdict().decision());
        assertEquals(1, a.verdict().trainBlockedFolds());
    }

    @Test
    void emptyFreeSpaceIsNotOptimizable() throws IOException {
        PresetSpaceConfig none = PresetSpaceConfig.defaults();
        for (PresetParam p : PresetParam.values()) {
            none = none.with(p.key(), false, null);
        }
        PresetSpaceConfig frozen = none;
        PresetOptimizationReport r = new PresetOptimizer(POOL, () -> frozen).run(wallet(List.of(link("BTC", csv("BTC", Integer.MAX_VALUE))), LIMITS),
                oneFold(), SearchControl.none());
        assertEquals(OptimizerDefaults.REASON_NO_FREE_PARAM, r.assets().getFirst().statusReason());
    }

    // ---------------------------------------------------------------- règle de décision

    private static FoldScore win(double delta) {
        return new FoldScore(true, true, false, false, delta);
    }

    private static FoldScore lose(double delta) {
        return new FoldScore(true, false, false, false, delta);
    }

    private static FoldScore blocked() {
        return new FoldScore(true, false, false, true, 0.0);
    }

    @Test
    void verdictAdoptsOnlyWithMajorityPositiveMedianNoVetoAndFewBlockedFolds() {
        assertEquals(OptimizerDefaults.VERDICT_ADOPT, PresetVerdict.decide(List.of(win(1), win(2), lose(-0.5), win(1.5), win(1)), 5).decision());
        // minorité de plis gagnés
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET, PresetVerdict.decide(List.of(win(3), lose(-1), lose(-1), lose(-1), win(2)), 5).decision());
        // veto en test
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET,
                PresetVerdict.decide(List.of(win(1), win(1), win(1), new FoldScore(true, false, true, false, 5.0)), 4).decision());
        // 2 plis sur 5 à entraînement vetoé = 40 % >= 30 %
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET, PresetVerdict.decide(List.of(win(1), win(1), win(1), blocked(), blocked()), 5).decision());
        // 1 pli sur 5 = 20 % < 30 %
        assertEquals(OptimizerDefaults.VERDICT_ADOPT, PresetVerdict.decide(List.of(win(1), win(1), win(1), win(1), blocked()), 5).decision());
        assertEquals(OptimizerDefaults.VERDICT_KEEP_PRESET, PresetVerdict.decide(List.of(), 0).decision());
    }

    // ---------------------------------------------------------------- cadences et budget

    @Test
    void cadenceSegmentsTileTheSameThreeYears() {
        LocalDate start = LocalDate.parse("2023-11-15");
        LocalDate end = LocalDate.parse("2026-11-15");
        assertEquals(36, PresetOptimizer.segmentCount(start, end, 30));
        assertEquals(12, PresetOptimizer.segmentCount(start, end, 90));
        assertEquals(6, PresetOptimizer.segmentCount(start, end, 180));
    }

    @Test
    void budgetCountsSharedTrainingEndsOnce() {
        PresetOptimizationReport.Timings timings = new PresetOptimizationReport.Timings(0, List.of(
                new PresetOptimizationReport.SearchTiming("BTC", LocalDate.parse("2024-05-15"), 20_000, 500),
                new PresetOptimizationReport.SearchTiming("BTC", LocalDate.parse("2024-11-15"), 40_000, 700)));
        AssetReport btc = new AssetReport("BTC", "x", OptimizerDefaults.STATUS_OPTIMIZABLE, null, List.of(), Map.of(), List.of(), List.of(), null, 0, 0);
        AssetReport paxg = new AssetReport("PAXG", "x", OptimizerDefaults.STATUS_NOT_OPTIMIZABLE, "HISTORIQUE_INSUFFISANT", List.of(), Map.of(),
                List.of(), List.of(), null, 0, 0);
        PresetOptimizationReport wf = new PresetOptimizationReport(null, List.of(btc, paxg), null, List.of(), "", List.of(), timings);
        PresetOptimizer.BudgetEstimate b = PresetOptimizer.estimateCadenceBudget(wf, PresetOptimizerConfig.defaults(), LocalDate.parse("2026-11-15"));
        assertEquals(1, b.optimizableAssets());
        assertEquals(36, b.distinctTrainEnds()); // la grille de 30 j contient celles de 90 j et de 180 j
        assertEquals(36, b.searches());
        assertEquals(600.0, b.trialsPerSearch(), 1e-9);
        assertEquals(36 * 30_000.0 / 60_000.0, b.estimatedMinutes(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> PresetOptimizer.estimateCadenceBudget(
                new PresetOptimizationReport(null, List.of(), null, List.of(), "", List.of(), null), PresetOptimizerConfig.defaults(), END));
    }

    // ---------------------------------------------------------------- demande admin

    @Test
    void presetModeIsParsedWithDefaults() {
        OptimizerRunRequest req = OptimizerRunRequest.presetL1(null, null, null, null, null, null);
        assertEquals(OptimizerRunRequest.Mode.PRESET_L1, req.resolvedMode());
        PresetOptimizerConfig cfg = PresetOptimizerConfig.of(req);
        assertEquals(StartingProfile.BALANCED, cfg.profile());
        assertEquals(6, cfg.foldEnds().size());
        assertEquals(6, cfg.testMonths());
        assertEquals(CalibrationConfig.V6_REFERENCE_AUTONOMY_DAYS, cfg.autonomyDays());
        assertEquals(List.of(30, 90, 180), cfg.cadenceDays());
        assertThrows(IllegalArgumentException.class, () -> PresetOptimizerConfig.of(OptimizerRunRequest.presetL1(null, "CUSTOM", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> PresetOptimizerConfig.of(OptimizerRunRequest.presetL1(null, "XXL", null, null, null, null)));
    }
}
