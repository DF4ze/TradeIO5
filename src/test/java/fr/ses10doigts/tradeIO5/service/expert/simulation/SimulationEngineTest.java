package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.EligibilityDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationResultDto;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowReplayColumns;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationEngineTest {

    private static final Instant TODAY = Instant.parse("2026-11-07T10:00:00Z");
    private static final SimulationInput.Provenance PROVENANCE =
            new SimulationInput.Provenance("BINANCE", MarketDataSource.BINANCE, "BTCUSDT", false);
    private static final EligibilityDto ELIGIBILITY = new EligibilityDto(150, List.of());

    private static SimulationInput input(SimulationType type, List<MarketData> candles, TrendRegime signal, LocalDate from, LocalDate to) {
        List<RainbowAtrParamSet> bearBull = RainbowAtrPresets.bearBull("BTC");
        return new SimulationInput(type, "BTC", candles, TrendMixCalculator.Params.defaults(), RangeMapping.KEEP_PREVIOUS, "Mix",
                bearBull.get(RainbowAtrPresets.BULL), bearBull.get(RainbowAtrPresets.BEAR), signal, from, to);
    }

    private static SimulationResultDto run(SimulationInput in) {
        return SimulationEngine.run(in, PROVENANCE, ELIGIBILITY, List.of());
    }

    @Test
    void trendAloneHasNoWalletNorLines() {
        SimulationResultDto r = run(input(SimulationType.TREND, SimulationFixtures.synthetic(700, TODAY), null, null, null));
        assertTrue(r.lines().isEmpty());
        assertEquals(List.of("o", "h", "l", "c", "tSma", "tUp", "tDn", "tReg", "tSmaReg", "tRaw", "tScore", "tSub0", "tSub1", "tSub2",
                "tOlsS0", "tOlsE0", "tOlsS1", "tOlsE1", "tOlsS2", "tOlsE2"), List.copyOf(r.chart().cols().keySet()));
        assertEquals(r.chart().d().size(), r.chart().trend().size());
        assertFalse(r.phases().isEmpty());
    }

    @Test
    void trendChartExposesSmaBandsAndBothRegimeTracks() {
        SimulationResultDto r = run(input(SimulationType.TREND, SimulationFixtures.synthetic(700, TODAY), null, null, null));
        var cols = r.chart().cols();
        int n = r.chart().d().size();
        for (String k : List.of("tSma", "tUp", "tDn", "tReg", "tSmaReg")) {
            assertEquals(n, cols.get(k).size(), k);
        }
        int last = n - 1;
        double sma = cols.get("tSma").get(last).doubleValue();
        assertTrue(cols.get("tUp").get(last).doubleValue() > sma);
        assertTrue(cols.get("tDn").get(last).doubleValue() < sma);
        assertTrue(cols.get("tReg").get(last).intValue() >= 1 && cols.get("tReg").get(last).intValue() <= 3);
        assertTrue(cols.get("tSmaReg").get(last).intValue() >= 1 && cols.get("tSmaReg").get(last).intValue() <= 3);
        double score = cols.get("tScore").get(last).doubleValue();
        assertTrue(score >= -1 && score <= 1);
        double mean = (cols.get("tSub0").get(last).doubleValue() + cols.get("tSub1").get(last).doubleValue() + cols.get("tSub2").get(last).doubleValue()) / 3;
        assertEquals(score, mean, 1e-9);
        assertEquals(0.30, r.chart().trendParams().get("enter").doubleValue(), 1e-9);
        assertTrue(cols.get("tOlsE0").get(last).doubleValue() > 0);
    }

    @Test
    void defaultRangeIsLast24Months() {
        SimulationResultDto r = run(input(SimulationType.AFFECTATION, SimulationFixtures.synthetic(1200, TODAY), null, null, null));
        LocalDate last = TODAY.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        assertEquals(last, r.meta().to());
        assertTrue(r.meta().from().isAfter(last.minusMonths(24).minusDays(1)));
        assertFalse(r.meta().from().isBefore(last.minusMonths(24)));
    }

    @Test
    void affectationResultIsComplete() {
        SimulationResultDto r = run(input(SimulationType.AFFECTATION, SimulationFixtures.synthetic(900, TODAY), null, null, null));
        assertEquals(14, r.lines().size());
        assertEquals(RainbowReplayColumns.COLS.keySet(), r.chart().cols().keySet());
        assertTrue(r.chart().cols().containsKey("reserve"));
        assertEquals(r.chart().d().size(), r.chart().wallet().total().size());
        assertEquals(r.chart().d().size(), r.chart().active().size());
        assertEquals(2, r.chart().sets().size());
    }

    @Test
    void rainbowOnSingleRegimeOpensNothingNewOutsideRegime() {
        SimulationResultDto r = run(input(SimulationType.RAINBOW, SimulationFixtures.synthetic(900, TODAY), TrendRegime.UP, null, null));
        List<Number> active = List.copyOf(r.chart().active());
        List<Number> armNowBuy = r.chart().cols().get("buyArmNow");
        List<Number> armNowSell = r.chart().cols().get("sellArmNow");
        List<Number> multZone = r.chart().cols().get("buyKind");
        boolean inactiveSeen = false;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).intValue() == 0) {
                inactiveSeen = true;
                assertEquals(0, armNowBuy.get(i).intValue(), "armement achat hors régime, jour " + i);
                assertEquals(0, armNowSell.get(i).intValue(), "armement vente hors régime, jour " + i);
                assertTrue(multZone.get(i) == null || multZone.get(i).intValue() != 1, "achat de zone hors régime, jour " + i);
            }
        }
        assertTrue(inactiveSeen, "la série de test doit contenir des barres hors régime");
    }

    @Test
    void phasesAndSwitchesAreConsistent() {
        SimulationResultDto r = run(input(SimulationType.TREND, SimulationFixtures.synthetic(900, TODAY), null, null, null));
        assertEquals(r.phases().size() - 1, r.switches().size());
        for (int i = 0; i < r.switches().size(); i++) {
            assertEquals(r.phases().get(i).regime(), r.switches().get(i).from());
            assertEquals(r.phases().get(i + 1).regime(), r.switches().get(i).to());
        }
        assertEquals(r.chart().d().size(), r.phases().stream().mapToInt(p -> p.bars()).sum());
    }

    @Test
    void rejectsReversedRange() {
        SimulationInput in = input(SimulationType.TREND, SimulationFixtures.synthetic(700, TODAY), null,
                LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-01"));
        try {
            run(in);
            throw new AssertionError("plage inversée acceptée");
        } catch (fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    /** Affectation sur les fixtures du rejeu CLI : mêmes lignes que {@code RainbowAtrReplayMain} (Mix, garder, Bear/Bull). */
    @Test
    void affectationMatchesReplayMain() throws IOException {
        LocalDate start = LocalDate.parse("2024-06-10");
        for (String asset : new String[]{"BTC", "ETH"}) {
            List<MarketData> candles = SimulationFixtures.csv(asset);
            RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
            TrendMixCalculator.Params mp = TrendMixCalculator.Params.defaults();
            TrendRegime[] regime = TrendMixCalculator.compute(candles, ds.sma(mp.smaPeriod()), ds.atr(mp.atrPeriod()), mp).regime();
            int startIdx = 0;
            while (candles.get(startIdx).getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(start)) {
                startIdx++;
            }
            List<DayRow> expected = RainbowAtrReplay.run(ds, RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]),
                    RainbowSetSelector.select(regime, RangeMapping.KEEP_PREVIOUS), startIdx, candles.size() - 1);

            List<RainbowAtrParamSet> sets = RainbowAtrPresets.bearBull(asset);
            SimulationInput in = new SimulationInput(SimulationType.AFFECTATION, asset, candles, mp, RangeMapping.KEEP_PREVIOUS, "Mix",
                    sets.get(RainbowAtrPresets.BULL), sets.get(RainbowAtrPresets.BEAR), null, start,
                    candles.getLast().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
            SimulationResultDto actual = run(in);

            assertEquals(expected.size(), actual.chart().d().size(), asset);
            for (Map.Entry<String, java.util.function.Function<DayRow, Object>> col : RainbowReplayColumns.COLS.entrySet()) {
                List<Number> got = actual.chart().cols().get(col.getKey());
                for (int i = 0; i < expected.size(); i++) {
                    Number want = SimulationEngine.number(col.getValue().apply(expected.get(i)));
                    assertEquals(want == null ? null : want.doubleValue(), got.get(i) == null ? null : got.get(i).doubleValue(),
                            asset + " " + col.getKey() + " ligne " + i);
                }
            }
            assertTrue(actual.chart().active().stream().allMatch(a -> a == 1), asset + " : toutes les barres actives en Affectation");
        }
    }
}
