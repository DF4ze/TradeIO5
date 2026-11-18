package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.Gain;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.RegimeBandPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Évaluateur : déterminisme, reproductibilité, fenêtres inéligibles, vetos, timing pur hors score. Données : CSV D1 du repo. */
class ReplayScorerTest {

    private static final LocalDate END = LocalDate.parse("2026-09-01");
    private static final RainbowModulationControl LIMITS = RainbowModulationControl.defaults(Instant.EPOCH, null);
    private static final ResolvedCandidate CANDIDATE = new ResolvedCandidate(new RegimeBandPolicy(20, 90, 0, 30), new AthCap(6.0, 2.0), 10.0);

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

    private static ScorerInputs inputs(List<MarketData> candles, LocalDate end) {
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
        LinkSignalReplay.Config cfg = new LinkSignalReplay.Config(ds, candles, TrendMixCalculator.Params.defaults(),
                RangeMapping.KEEP_PREVIOUS, sets, 0, 0, candles.size() - 1, null, Map.of(), Map.of(), Set.of(), 6);
        LiveSlot slot = new LiveSlot(1L, 1L, "BTC", 1L, 1, 100.0, null);
        return ScorerInputs.of(List.of(new ScorerInputs.AssetInput(slot, cfg, "TEST")), 5000.0, end, StartingProfile.BALANCED, LIMITS);
    }

    @Test
    void sameInputsGiveIdenticalReports() throws IOException {
        ScorerInputs in = inputs(csv(Integer.MAX_VALUE), END);
        ScoreReport a = ReplayScorer.score(in, CANDIDATE, CostMode.SIMPLIFIED, false);
        ScoreReport b = ReplayScorer.score(in, CANDIDATE, CostMode.SIMPLIFIED, false);
        assertEquals(a, b);
        assertEquals(List.of("12m", "24m", "full"), a.windows().stream().map(WindowReport::name).toList());
        assertEquals(List.of(3, 2, 1), a.windows().stream().map(WindowReport::weight).toList());
        assertTrue(a.excluded().isEmpty());
        assertTrue(a.windows().stream().allMatch(w -> w.to().equals(END) && w.cash0() == 5000.0));
    }

    @Test
    void fingerprintExposesTheCandlesUsed() throws IOException {
        List<MarketData> all = csv(Integer.MAX_VALUE);
        ScoreReport r = ReplayScorer.score(inputs(all, END), CANDIDATE, CostMode.SIMPLIFIED, false);
        CandleFingerprint f = r.fingerprint().getFirst();
        assertEquals("BTC", f.asset());
        assertEquals(END, f.lastDay());
        assertEquals(LocalDate.parse("2017-08-17"), f.firstDay());
        // une bougie modifiée change la somme de contrôle, une bougie postérieure à la date de fin ne la change pas
        assertEquals(f, ReplayScorer.score(inputs(all, END), CANDIDATE, CostMode.SIMPLIFIED, true).fingerprint().getFirst());
        ScoreReport earlier = ReplayScorer.score(inputs(all, END.minusDays(1)), CANDIDATE, CostMode.SIMPLIFIED, false);
        assertNotEquals(f.checksum(), earlier.fingerprint().getFirst().checksum());
        assertEquals(f.count() - 1, earlier.fingerprint().getFirst().count());
    }

    @Test
    void ineligibleWindowsAreFlaggedNeverReplaced() throws IOException {
        ScoreReport r = ReplayScorer.score(inputs(csv(900), LocalDate.parse("2020-01-31")), CANDIDATE, CostMode.SIMPLIFIED, false);
        assertFalse(r.excluded().isEmpty());
        assertEquals(3, r.windows().size() + r.excluded().size());
        for (WindowReport w : r.windows()) {
            assertTrue(r.excluded().stream().noneMatch(e -> e.startsWith(w.name() + " :")));
        }
    }

    @Test
    void noEligibleWindowIsVetoed() throws IOException {
        ScoreReport r = ReplayScorer.score(inputs(csv(120), LocalDate.parse("2017-12-01")), CANDIDATE, CostMode.SIMPLIFIED, false);
        assertTrue(r.vetoed());
        assertEquals(List.of(OptimizerDefaults.VETO_NO_WINDOW), r.vetoes());
        assertEquals(OptimizerDefaults.VETO_SCORE, r.score());
    }

    @Test
    void finalStakeVetoReplacesTheScore() throws IOException {
        ScorerInputs in = inputs(csv(Integer.MAX_VALUE), END).withMaxFinalStake(0.0001);
        ScoreReport r = ReplayScorer.score(in, CANDIDATE, CostMode.SIMPLIFIED, false);
        assertTrue(r.vetoed());
        assertTrue(r.vetoes().contains(OptimizerDefaults.VETO_FINAL_STAKE));
        assertEquals(OptimizerDefaults.VETO_SCORE, r.score());
        assertNotEquals(OptimizerDefaults.VETO_SCORE, r.rawScore());
    }

    @Test
    void pureTimingIsReportedButNeverInTheScore() throws IOException {
        ScoreReport r = ReplayScorer.score(inputs(csv(Integer.MAX_VALUE), END), CANDIDATE, CostMode.SIMPLIFIED, false);
        assertNotNull(r.pureTiming());
        assertTrue(r.windows().stream().allMatch(w -> w.timingGainPct() != null));
        assertEquals(r.medianGainPct() - r.penalty(), r.rawScore(), 1e-12);
        if (!r.vetoed()) {
            assertEquals(r.rawScore(), r.score());
        }
    }

    @Test
    void completeCostsModeIsDeterministicToo() throws IOException {
        ScorerInputs in = inputs(csv(Integer.MAX_VALUE), END);
        ScoreReport a = ReplayScorer.score(in, CANDIDATE, CostMode.COMPLETE, false);
        assertEquals(a, ReplayScorer.score(in, CANDIDATE, CostMode.COMPLETE, false));
        assertEquals("COMPLETE", a.costMode());
    }

    // ---------------------------------------------------------------- vetos (fonction pure)

    private static WindowReport window(String name, LocalDate from, double drawdown, double minCash, double finalStake, boolean neverSells,
                                       boolean neverRebuys) {
        return window(name, 12, from, drawdown, minCash, finalStake, neverSells, neverRebuys);
    }

    private static WindowReport window(String name, Integer months, LocalDate from, double drawdown, double minCash, double finalStake,
                                       boolean neverSells, boolean neverRebuys) {
        return new WindowReport(name, months, 1, from, END, 1000, 900, 100, 11.0, drawdown, 50, new Gain(0, 0, 0, 0), 0, 0, 5, 5, 1.0,
                finalStake, 0, neverSells, neverRebuys, minCash, 500, 1000, 0.0);
    }

    @Test
    void vetoesAreDetectedOnEachCriterion() {
        Vetoes vetoes = new Vetoes(-30.0, 25.0);
        LocalDate from = LocalDate.parse("2025-09-01");
        assertTrue(vetoes.evaluate(List.of(window("w", from, -20, 10, 1, false, false))).isEmpty());
        assertEquals(List.of(OptimizerDefaults.VETO_DRAWDOWN), vetoes.evaluate(List.of(window("w", from, -45, 10, 1, false, false))));
        assertEquals(List.of(OptimizerDefaults.VETO_CAPITAL), vetoes.evaluate(List.of(window("w", from, -20, -5, 1, false, false))));
        assertEquals(List.of(OptimizerDefaults.VETO_FINAL_STAKE), vetoes.evaluate(List.of(window("w", from, -20, 10, 40, false, false))));
        assertEquals(List.of(OptimizerDefaults.VETO_DEGENERATE), vetoes.evaluate(List.of(window("w", from, -20, 10, 1, true, false))));
        assertEquals(List.of(OptimizerDefaults.VETO_DEGENERATE), vetoes.evaluate(List.of(window("w", from, -20, 10, 1, false, true))));
    }

    @Test
    void degenerateIsJudgedOnTheLongestWindowOnly() {
        Vetoes vetoes = new Vetoes(null, null);
        WindowReport shortW = window("12m", LocalDate.parse("2025-09-01"), -10, 10, 1, true, true);
        WindowReport longW = window("full", LocalDate.parse("2018-01-01"), -10, 10, 1, false, false);
        assertTrue(vetoes.evaluate(List.of(shortW, longW)).isEmpty());
        assertEquals(List.of(OptimizerDefaults.VETO_DEGENERATE), vetoes.evaluate(List.of(longW, window("full", LocalDate.parse("2017-01-01"),
                -10, 10, 1, true, false))));
    }

    @Test
    void drawdownVetoIgnoresTheFullWindow() {
        Vetoes vetoes = new Vetoes(-30.0, null);
        LocalDate from = LocalDate.parse("2018-01-01");
        WindowReport full = window("full", null, from, -80, 10, 1, false, false);
        assertTrue(vetoes.evaluate(List.of(full)).isEmpty());
        assertTrue(vetoes.evaluate(List.of(full, window("12m", LocalDate.parse("2025-09-01"), -10, 10, 1, false, false))).isEmpty());
        assertEquals(List.of(OptimizerDefaults.VETO_DRAWDOWN),
                vetoes.evaluate(List.of(full, window("24m", 24, LocalDate.parse("2024-09-01"), -45, 10, 1, false, false))));
    }

    @Test
    void fullWindowIsReportedButNeverInScoreOrDispersion() throws IOException {
        ScoreReport r = ReplayScorer.score(inputs(csv(Integer.MAX_VALUE), END), CANDIDATE, CostMode.SIMPLIFIED, false);
        assertEquals(List.of("12m", "24m", "full"), r.windows().stream().map(WindowReport::name).toList());
        List<WeightedStats.Point> bounded = r.windows().stream().filter(WindowReport::scored)
                .map(w -> new WeightedStats.Point(w.gainPct(), w.weight())).toList();
        assertEquals(2, bounded.size());
        double median = WeightedStats.median(bounded);
        assertEquals(median, r.medianGainPct(), 1e-12);
        assertEquals(WeightedStats.meanAbsoluteDeviation(bounded, median), r.dispersionPct(), 1e-12);
        assertEquals(median - OptimizerDefaults.DISPERSION_PENALTY * r.dispersionPct(), r.rawScore(), 1e-12);
        // seule la fenêtre complète éligible : rien à noter
        ScorerInputs onlyFull = inputs(csv(Integer.MAX_VALUE), END).withWindows(List.of(new WindowSpec("full", null, 1)));
        ScoreReport f = ReplayScorer.score(onlyFull, CANDIDATE, CostMode.SIMPLIFIED, false);
        assertEquals(List.of(OptimizerDefaults.VETO_NO_WINDOW), f.vetoes());
        assertEquals(OptimizerDefaults.VETO_SCORE, f.score());
        assertEquals(1, f.windows().size());
    }

    @Test
    void fixedReferenceNeverSpendsBeyondTheCommonCash() throws IOException {
        for (double referenceBase : new double[]{47.61, 5_000.0}) {
            ScorerInputs in0 = inputs(csv(Integer.MAX_VALUE), END);
            ScorerInputs in = in0.withAssets(in0.assets().stream().map(a -> a.withReferenceBase(referenceBase)).toList()).withProfile(StartingProfile.FULL);
            for (CostMode mode : CostMode.values()) {
                ScoreReport r = ReplayScorer.score(in, CANDIDATE, mode, false);
                for (WindowReport w : r.windows()) {
                    // pas de dépense au-delà du cash, pas de recharge : le cash du DCA fixe reste dans [0, capital]
                    assertTrue(w.fixedMinCash() >= -1e-6, w.name() + " " + mode + " base " + referenceBase + " : " + w.fixedMinCash());
                    assertTrue(w.minCash() >= -1e-6);
                    if (w.scored()) {
                        double fixedGainPct = 100.0 * (w.fixedFinalValue() - w.cash0()) / w.cash0();
                        assertTrue(Math.abs(fixedGainPct) <= 100.0, w.name() + " gain DCA fixe " + fixedGainPct);
                    }
                }
            }
        }
    }
}
