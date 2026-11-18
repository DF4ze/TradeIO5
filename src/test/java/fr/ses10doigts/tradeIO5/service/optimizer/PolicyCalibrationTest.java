package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.RegimeBandPolicy;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.PolicyVerdict.FoldScore;
import fr.ses10doigts.tradeIO5.service.optimizer.ScoreReport.WindowReport;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape 4 : référence commune du gain, plis walk-forward, règle de décision, candidats invalides. Données : CSV D1 du repo. */
class PolicyCalibrationTest {

    private static final LocalDate END = LocalDate.parse("2026-09-01");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);

    private static List<MarketData> csv(int limit) throws IOException {
        List<MarketData> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("tools/rainbow-replay/data/d1_BTC.csv"))) {
            if (out.size() >= limit) {
                break;
            }
            String[] p = line.split(",");
            out.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochMilli(Long.parseLong(p[0]))).pair("BTCUSDC")
                    .open(new BigDecimal(p[1])).high(new BigDecimal(p[2])).low(new BigDecimal(p[3])).close(new BigDecimal(p[4]))
                    .volume(BigDecimal.ZERO).build());
        }
        return out;
    }

    private static ScorerInputs.AssetInput link(String asset, List<MarketData> candles) {
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(),
                RangeMapping.KEEP_PREVIOUS, sets, 0, 0, candles.size() - 1, null, Map.of(), Map.of(), Set.of(), 6);
        return new ScorerInputs.AssetInput(new LiveSlot(1L, 1L, asset, 1L, 1, 100.0, null), cfg, "TEST", 1.5, 10.0);
    }

    private static ScorerInputs inputs(List<ScorerInputs.AssetInput> assets, LocalDate end) {
        return ScorerInputs.of(assets, 10_000.0, end, StartingProfile.BALANCED, LIMITS);
    }

    private static ResolvedCandidate candidate(double multiplier) {
        return new ResolvedCandidate(new RegimeBandPolicy(20, 90, 0, 30), new AthCap(6.0, 2.0), multiplier);
    }

    // ---------------------------------------------------------------- scorer : référence commune

    @Test
    void candidatesWithDifferentBaseMultipliersShareTheSameFixedDcaReference() throws IOException {
        ScorerInputs in = inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), END);
        ScoreReport one = ReplayScorer.score(in, candidate(1.0), CostMode.SIMPLIFIED, false);
        ScoreReport two = ReplayScorer.score(in, candidate(2.0), CostMode.SIMPLIFIED, false);
        for (int i = 0; i < one.windows().size(); i++) {
            WindowReport a = one.windows().get(i);
            WindowReport b = two.windows().get(i);
            assertEquals(a.name(), b.name());
            // même DCA fixe de référence ; gain = (valeur finale - DCA fixe) / capital
            assertEquals(a.fixedFinalValue(), b.fixedFinalValue(), 1e-9);
            assertEquals(100.0 * (a.finalValue() - a.fixedFinalValue()) / a.cash0(), a.gainPct(), 1e-9);
            assertEquals(100.0 * (b.finalValue() - b.fixedFinalValue()) / b.cash0(), b.gainPct(), 1e-9);
        }
        assertNotEquals(one.medianGainPct(), two.medianGainPct());
    }

    @Test
    void scoreIsReproducibleAndNeverTouchesFutureCandles() throws IOException {
        List<MarketData> all = csv(Integer.MAX_VALUE);
        LocalDate t = LocalDate.parse("2023-11-15");
        ScorerInputs full = inputs(List.of(link("BTC", all)), t);
        ScorerInputs cut = PolicyCalibrator.truncated(full, t);
        ScoreReport a = ReplayScorer.score(full, candidate(1.0), CostMode.SIMPLIFIED, false);
        ScoreReport b = ReplayScorer.score(cut, candidate(1.0), CostMode.SIMPLIFIED, false);
        assertEquals(a.windows().stream().map(WindowReport::gainPct).toList(), b.windows().stream().map(WindowReport::gainPct).toList());
        assertEquals(b, ReplayScorer.score(cut, candidate(1.0), CostMode.SIMPLIFIED, false));
        assertTrue(cut.assets().getFirst().template().candles().stream()
                .noneMatch(c -> c.getTimestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate().isAfter(t)));
    }

    // ---------------------------------------------------------------- plis

    @Test
    void testWindowStartsAfterTrainingEndWithoutOverlap() throws IOException {
        LocalDate t = LocalDate.parse("2023-11-15");
        CalibrationConfig cfg = CalibrationConfig.defaults();
        assertEquals(t.plusMonths(12), cfg.testEnd(t));
        ScorerInputs train = PolicyCalibrator.truncated(inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), t), t);
        ScoreReport r = ReplayScorer.score(train, candidate(1.0), CostMode.SIMPLIFIED, false);
        assertTrue(r.windows().stream().allMatch(w -> !w.to().isAfter(t)));
        ScorerInputs test = inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), cfg.testEnd(t))
                .withWindows(List.of(new WindowSpec("test", null, 1, t.plusDays(1))));
        ScoreReport rt = ReplayScorer.score(test, candidate(1.0), CostMode.SIMPLIFIED, false);
        assertEquals(1, rt.windows().size());
        assertTrue(rt.windows().getFirst().from().isAfter(t));
    }

    @Test
    void linkWithoutEnoughHistoryIsFlagged() throws IOException {
        ScorerInputs in = inputs(List.of(link("BTC", csv(Integer.MAX_VALUE)), link("PAXG", csv(200))), LocalDate.parse("2023-11-15"));
        Map<String, String> ineligible = ReplayScorer.ineligibleLinks(in, WindowSpec.defaults());
        assertTrue(ineligible.containsKey("PAXG"));
        assertFalse(ineligible.containsKey("BTC"));
        List<MarketData> shortHistory = csv(200);
        LocalDate lastDay = shortHistory.getLast().getTimestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        ScorerInputs shortIn = inputs(List.of(link("BTC", csv(Integer.MAX_VALUE)), link("PAXG", shortHistory)), lastDay);
        List<ScoreReport.HistoryStatus> history = ReplayScorer.historyStatuses(shortIn, WindowSpec.defaults());
        ScoreReport.HistoryStatus paxg = history.stream().filter(h -> h.asset().equals("PAXG")).findFirst().orElseThrow();
        assertEquals(OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY, paxg.status());
        assertTrue(paxg.candlesAvailable() < paxg.candlesRequired());
    }

    // ---------------------------------------------------------------- règle de décision

    private static FoldScore win(double gain) {
        return new FoldScore(true, true, false, gain);
    }

    private static FoldScore loss(double gain) {
        return new FoldScore(true, false, false, gain);
    }

    @Test
    void majorityOfWinsWithNonNegativeMedianAdopts() {
        Verdict v = PolicyVerdict.decide(List.of(win(2), win(1), loss(-1)), 3);
        assertEquals(OptimizerDefaults.VERDICT_ADOPT, v.decision());
        assertEquals(2, v.wins());
        assertEquals(2, v.majority());
    }

    @Test
    void minorityOfWinsKeepsTheStart() {
        Verdict v = PolicyVerdict.decide(List.of(win(2), loss(1), loss(3)), 3);
        assertEquals(OptimizerDefaults.VERDICT_KEEP_START, v.decision());
    }

    @Test
    void negativeMedianKeepsTheStartEvenWithMajority() {
        Verdict v = PolicyVerdict.decide(List.of(win(-1), win(-2), loss(5)), 3);
        assertEquals(OptimizerDefaults.VERDICT_KEEP_START, v.decision());
    }

    @Test
    void vetoInTestKeepsTheStart() {
        Verdict v = PolicyVerdict.decide(List.of(win(2), win(1), new FoldScore(true, false, true, 3.0)), 3);
        assertEquals(OptimizerDefaults.VERDICT_KEEP_START, v.decision());
        assertTrue(v.vetoInTest());
    }

    @Test
    void unevaluatedFoldCountsAgainstTheMajority() {
        Verdict v = PolicyVerdict.decide(List.of(win(2), new FoldScore(false, false, false, null), loss(1)), 3);
        assertEquals(OptimizerDefaults.VERDICT_KEEP_START, v.decision());
    }

    // ---------------------------------------------------------------- candidats invalides

    @Test
    void invalidBandsAreRejectedBeforeAnyReplay() throws IOException {
        var validator = L2Params.validator(LIMITS);
        Candidate ok = new Candidate(Map.of(L2Params.BULL_MIN, 20.0, L2Params.BULL_MAX, 90.0, L2Params.BEAR_MIN, 0.0, L2Params.BEAR_MAX, 30.0));
        assertTrue(validator.apply(ok).isEmpty());
        Candidate inverted = ok.with(L2Params.BULL_MIN, 95.0);
        assertFalse(validator.apply(inverted).isEmpty());
        Candidate narrow = ok.with(L2Params.BEAR_MAX, 1.0);
        assertFalse(validator.apply(narrow).isEmpty());

        ReplayObjective objective = new ReplayObjective(inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), END),
                L2Params.adapter(StartingProfile.BALANCED), validator, List.of(), Map.of());
        Evaluation e = objective.evaluate(inverted.with(L2Params.BUY_CAP, 6.0).with(L2Params.BASE_MULTIPLIER, 1.0), Fidelity.ALL_WINDOWS_SIMPLIFIED,
                Scenario.BASE);
        assertTrue(e.vetoed());
        assertEquals(List.of(OptimizerDefaults.VETO_INVALID), e.vetoes());
        assertEquals(OptimizerDefaults.VETO_SCORE, e.score());
    }

    @Test
    void blockedTrainingNeverRetainsACandidate() throws IOException {
        ScoreReport healthy = ReplayScorer.score(inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), END), candidate(1.0), CostMode.SIMPLIFIED, false);
        assertFalse(healthy.vetoed());
        Evaluation ok = Evaluation.ok(5.0, null);
        Evaluation vetoed = new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(OptimizerDefaults.VETO_DRAWDOWN), null);
        Evaluation invalid = new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(OptimizerDefaults.VETO_INVALID), null);
        assertFalse(PolicyCalibrator.trainBlocked(ok, healthy));
        assertTrue(PolicyCalibrator.trainBlocked(vetoed, healthy));
        assertTrue(PolicyCalibrator.trainBlocked(invalid, healthy));
        ScoreReport startVetoed = ReplayScorer.score(inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), END).withMaxFinalStake(0.0),
                candidate(1.0), CostMode.SIMPLIFIED, false);
        assertTrue(startVetoed.vetoed());
        assertTrue(PolicyCalibrator.trainBlocked(ok, startVetoed));
    }

    @Test
    void foldWithEveryCandidateVetoedKeepsTheStartAndNeverRetainsAnInvalidCandidate() throws IOException {
        ScorerInputs in = inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), LocalDate.parse("2024-11-15")).withMaxFinalStake(0.0);
        CalibrationConfig cfg = new CalibrationConfig(List.of(StartingProfile.BALANCED), List.of(LocalDate.parse("2022-11-15")), 12,
                SearchConfig.defaults().withSobolSamples(16));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            PolicyCalibrationReport report = new PolicyCalibrator(pool).run(new ReferenceWallet(in, 90), cfg, SearchControl.none());
            PolicyCalibrationReport.ProfileReport profile = report.profiles().getFirst();
            PolicyCalibrationReport.FoldResult fold = profile.folds().getFirst();
            assertEquals(OptimizerDefaults.VERDICT_KEEP_START, fold.foldDecision());
            assertEquals(OptimizerDefaults.FOLD_TRAIN_VETOED, fold.foldReason());
            assertEquals(null, fold.chosen());
            assertFalse(fold.won());
            assertEquals(OptimizerDefaults.VERDICT_KEEP_START, profile.verdict().decision());
            assertEquals(null, profile.recommended());
            assertTrue(profile.folds().stream().noneMatch(f -> f.trainVetoes().contains(OptimizerDefaults.VETO_INVALID) && f.chosen() != null));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void referenceBaseSplitsCashEquallyAcrossLinks() {
        assertEquals(ReferenceBase.of(10_000.0, 1, 90, 1.5) / 3, ReferenceBase.of(10_000.0, 3, 90, 1.5), 0.05);
    }

    @Test
    void reportIsByteReproducibleExceptTimingsAndCountsInvalidTrials() throws IOException {
        ScorerInputs in = inputs(List.of(link("BTC", csv(Integer.MAX_VALUE))), LocalDate.parse("2024-11-15"));
        ReferenceWallet wallet = new ReferenceWallet(in, 90);
        CalibrationConfig cfg = new CalibrationConfig(List.of(StartingProfile.BALANCED), List.of(LocalDate.parse("2022-11-15")), 12,
                SearchConfig.defaults().withSobolSamples(16));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            PolicyCalibrationReport a = new PolicyCalibrator(pool).run(wallet, cfg, SearchControl.none());
            PolicyCalibrationReport b = new PolicyCalibrator(pool).run(wallet, cfg, SearchControl.none());
            assertEquals(a.toJsonWithoutTimings(), b.toJsonWithoutTimings());
            assertTrue(a.trials() > 0);
            assertEquals(1, a.profiles().size());
            assertEquals(1, a.profiles().getFirst().folds().size());
        } finally {
            pool.shutdownNow();
        }
    }
}
