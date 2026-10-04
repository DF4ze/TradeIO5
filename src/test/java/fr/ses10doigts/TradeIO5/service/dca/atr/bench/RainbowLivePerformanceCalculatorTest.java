package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePerformanceCalculator.Performance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RainbowLivePerformanceCalculator")
class RainbowLivePerformanceCalculatorTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final double EPS = 1e-9;

    private static RainbowAtrConfig config(double base) {
        return RainbowAtrConfig.builder().baseAmount(base).build();
    }

    private static RainbowLivePassBlock block(double close, RainbowLiveAction type, Double amount, Double qty,
                                              Double cashAfter, Double positionAfter) {
        return RainbowLivePassBlock.builder().close(close).zone(2).actionType(type).actionAmountUsdc(amount)
                .actionQuantity(qty).actionPrice(close).cashAfter(cashAfter).positionAfter(positionAfter).build();
    }

    private static RainbowLiveRun run(int dayOffset, RainbowLivePassBlock b2355, RainbowLivePassBlock b0005, double base) {
        return RainbowLiveRun.builder().day(D1.plusDays(dayOffset)).pass2355(b2355).pass0005(b0005).config(config(base)).build();
    }

    /** J0 achat 100@100, J1 achat 100@200, (J2 absent), J3 vente 0,75 @300 = 225, J4 rien @400, J5 : 00:05 seul (ignoré). */
    private static List<RainbowLiveRun> scenario() {
        List<RainbowLiveRun> runs = new ArrayList<>();
        runs.add(run(0, block(100, RainbowLiveAction.BUY, 100.0, 1.0, 900.0, 1.0), null, 10));
        runs.add(run(1, block(200, RainbowLiveAction.BUY, 100.0, 0.5, 800.0, 1.5), null, 10));
        runs.add(run(3, block(300, RainbowLiveAction.SELL, 225.0, 0.75, 1025.0, 0.75), null, 10));
        runs.add(run(4, block(400, RainbowLiveAction.NONE, null, null, 1025.0, 0.75), null, 10));
        runs.add(run(5, null, block(999, RainbowLiveAction.BUY, 50.0, 0.05, null, null), 10));
        return runs;
    }

    @Test
    @DisplayName("Achats + vente partielle + jour manquant + 00:05 seul ignoré : valeurs calculées à la main")
    void partialSell() {
        Performance p = RainbowLivePerformanceCalculator.compute(scenario(), 1000);
        var m = p.metrics();

        assertEquals(4, p.days());
        assertEquals(D1, p.firstDay());
        assertEquals(D1.plusDays(4), p.lastDay());
        assertEquals(200, m.invested(), EPS);
        assertEquals(225, m.saleProceeds(), EPS);
        assertEquals(0.75, m.position(), EPS);
        assertEquals(100, m.costBasis(), EPS);          // 200 − 200 × 0,75/1,5
        assertEquals(125, m.realizedGain(), EPS);       // 225 − 100
        assertEquals(300, m.currentValue(), EPS);       // 0,75 × 400
        assertEquals(200, m.potentialGain(), EPS);      // 300 − 100
        assertEquals(325, m.totalGain(), EPS);          // 225 + 300 − 200
        assertEquals(162.5, m.pnlPercent(), EPS);
        assertEquals(62.5, m.realizedPercent(), EPS);
        assertEquals(100, m.potentialPercent(), EPS);

        // DCA fixe : 10 à chaque jour avec bloc 23:55 (J0 100, J1 200, J3 300, J4 400), jour manquant non comblé
        double fixedQty = 10.0 / 100 + 10.0 / 200 + 10.0 / 300 + 10.0 / 400;
        assertEquals(40, m.fixedInvested(), EPS);
        assertEquals(fixedQty, m.fixedQuantity(), EPS);
        assertEquals(fixedQty * 400 - 40, m.fixedGain(), EPS);
        assertEquals((fixedQty * 400 - 40) / 40 * 100, m.fixedPnlPercent(), EPS);
        assertEquals(325 - (fixedQty * 400 - 40), m.outperformanceGain(), EPS);
        assertEquals(162.5 - m.fixedPnlPercent(), m.outperformancePoints(), EPS);

        // wallet mock : stocké par le 23:55 du dernier jour
        assertEquals(1025, p.wallet().cashUsdc(), EPS);
        assertEquals(0.75, p.wallet().positionQuantity(), EPS);
        assertEquals(1325, p.wallet().equityUsdc(), EPS);
        assertEquals(32.5, p.wallet().pnlPercent(), EPS);

        // séries / marqueurs
        assertEquals(4, p.series().size());
        assertEquals(D1.plusDays(3), p.series().get(2).day());
        assertEquals(1.5, p.series().get(1).position(), EPS);
        assertEquals(p.series().get(1).invested(), p.series().get(1).costBasis(), EPS); // aucune vente avant le jour 2
        assertEquals(3, p.markers().size());
        assertEquals(RainbowLiveAction.SELL, p.markers().getLast().type());
        assertEquals(300, p.markers().getLast().price(), EPS);
        assertEquals(225, p.markers().getLast().amountUsdc(), EPS);
    }

    @Test
    @DisplayName("Vente totale : restant 0, gain réalisé = gain total")
    void fullSell() {
        List<RainbowLiveRun> runs = scenario();
        runs.add(run(6, block(500, RainbowLiveAction.SELL, 375.0, 0.75, 1400.0, 0.0), null, 10));
        var m = RainbowLivePerformanceCalculator.compute(runs, 1000).metrics();

        assertEquals(0, m.position(), EPS);
        assertEquals(0, m.costBasis(), EPS);
        assertEquals(0, m.currentValue(), EPS);
        assertEquals(600, m.saleProceeds(), EPS);
        assertEquals(400, m.realizedGain(), EPS);       // 125 + (375 − 100)
        assertEquals(400, m.totalGain(), EPS);
        assertEquals(0, m.potentialGain(), EPS);
        assertEquals(200, m.pnlPercent(), EPS);
    }

    @Test
    @DisplayName("Aucun run : métriques à 0, séries vides, wallet = capital initial")
    void noRuns() {
        Performance p = RainbowLivePerformanceCalculator.compute(List.of(), 1000);
        assertEquals(0, p.days());
        assertNull(p.firstDay());
        assertTrue(p.series().isEmpty());
        assertTrue(p.markers().isEmpty());
        assertEquals(0, p.metrics().invested(), 0.0);
        assertEquals(0, p.metrics().pnlPercent(), 0.0);
        assertEquals(1000, p.wallet().equityUsdc(), EPS);
        assertEquals(0, p.wallet().pnlPercent(), 0.0);
    }

    @Test
    @DisplayName("Investi = 0 (que des NONE) : pourcentages 0, pas de NaN/Inf")
    void zeroInvested() {
        List<RainbowLiveRun> runs = List.of(
                run(0, block(100, RainbowLiveAction.NONE, null, null, 1000.0, 0.0), null, 10),
                run(1, block(110, RainbowLiveAction.NONE, null, null, 1000.0, 0.0), null, 10));
        var m = RainbowLivePerformanceCalculator.compute(runs, 1000).metrics();
        assertEquals(0, m.pnlPercent(), 0.0);
        assertEquals(0, m.realizedPercent(), 0.0);
        assertEquals(0, m.potentialPercent(), 0.0);
        assertFalse(Double.isNaN(m.outperformancePoints()) || Double.isInfinite(m.outperformancePoints()));
        assertEquals(20, m.fixedInvested(), EPS);   // le DCA fixe, lui, investit
    }

    @Test
    @DisplayName("Cohérence : position rejouée == positionAfter stocké (chaque jour)")
    void replayedPositionMatchesStored() {
        List<RainbowLiveRun> runs = scenario();
        Performance p = RainbowLivePerformanceCalculator.compute(runs, 1000);
        List<RainbowLiveRun> played = runs.stream().filter(r -> r.getPass2355() != null).toList();
        for (int i = 0; i < played.size(); i++) {
            assertEquals(played.get(i).getPass2355().getPositionAfter(), p.series().get(i).position(), EPS, "jour " + i);
        }
    }

    @Test
    @DisplayName("Vente > position (données incohérentes) : plafonnée, jamais de position négative")
    void sellCappedToPosition() {
        List<RainbowLiveRun> runs = List.of(
                run(0, block(100, RainbowLiveAction.BUY, 100.0, 1.0, 900.0, 1.0), null, 10),
                run(1, block(100, RainbowLiveAction.SELL, 500.0, 5.0, 1400.0, 0.0), null, 10));
        var m = RainbowLivePerformanceCalculator.compute(runs, 1000).metrics();
        assertEquals(0, m.position(), EPS);
        assertEquals(0, m.costBasis(), EPS);
    }

    // ---------------------------------------------------------------- parité avec le moteur

    private static List<MarketData> candles(double[] closes) {
        List<MarketData> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(MarketData.builder().timeFrame(TimeFrame.D1)
                    .timestamp(D1.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant()).pair("X")
                    .open(BigDecimal.valueOf(c)).high(BigDecimal.valueOf(c + 1)).low(BigDecimal.valueOf(c - 1))
                    .close(BigDecimal.valueOf(c)).volume(BigDecimal.ONE).build());
        }
        return out;
    }

    @Test
    @DisplayName("Parité : rejeu des events du moteur => mêmes agrégats que RainbowAtrResult (investi, vendu, restant, réalisé, PnL %, DCA fixe)")
    void parityWithEngine() {
        int n = 150;
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = 100 + 25 * Math.sin(i / 7.0) + i * 0.15;
        }
        RainbowAtrTuning t = new RainbowAtrTuning(5, 5, 1.5, 0.5, 0.5, 1.0, 1.5,
                ReentryMode.IMMEDIATE, ReentryMode.IMMEDIATE, 3, 5, 0, 1, 0.5, true, false, false);
        RainbowAtrGlobals g = new RainbowAtrGlobals(false, 60, 30, 0.5, 2.0, 2.0, 0.5, false, 50, false, 15, 100,
                2.0, 1.0, 0.5, 3.0, 10.0);
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles(closes));
        int start = RainbowAtrDataset.warmup(t.smaPeriod(), t.atrPeriod());
        RainbowAtrResult r = RainbowAtrEngine.simulate(ds, g, new RainbowAtrTuning[]{t}, null, start, n - 1, true);

        assertTrue(r.events().stream().anyMatch(e -> e.type().startsWith("BUY")), "scénario sans achat");
        assertTrue(r.events().stream().anyMatch(e -> e.type().equals("SELL")), "scénario sans vente");

        List<RainbowLiveRun> runs = new ArrayList<>();
        for (int i = start; i <= n - 1; i++) {
            final int idx = i;
            var events = r.events().stream().filter(e -> e.index() == idx
                    && (e.type().startsWith("BUY_") || e.type().equals("SELL") || e.type().equals("MOON_STOP"))).toList();
            assertTrue(events.size() <= 1, "achat+vente sur la même bougie : scénario à ajuster (jour " + idx + ")");
            RainbowLivePassBlock b;
            if (events.isEmpty()) {
                b = block(closes[i], RainbowLiveAction.NONE, null, null, null, null);
            } else if (events.getFirst().type().startsWith("BUY_")) {
                b = block(closes[i], RainbowLiveAction.BUY, events.getFirst().amount(), events.getFirst().quantity(), null, null);
            } else {
                b = block(closes[i], RainbowLiveAction.SELL, events.getFirst().amount(), events.getFirst().quantity(), null, null);
            }
            runs.add(run(i, b, null, 10.0));
        }
        var m = RainbowLivePerformanceCalculator.compute(runs, 1000).metrics();

        assertEquals(r.invested(), m.invested(), 1e-6);
        assertEquals(r.saleProceeds(), m.saleProceeds(), 1e-6);
        assertEquals(r.currentValue(), m.currentValue(), 1e-6);
        assertEquals(r.realizedGain(), m.realizedGain(), 1e-6);
        assertEquals(r.potentialGain(), m.potentialGain(), 1e-6);
        assertEquals(r.totalGain(), m.totalGain(), 1e-6);
        assertEquals(r.pnlPercent(), m.pnlPercent(), 1e-6);
        assertEquals(r.realizedPercent(), m.realizedPercent(), 1e-6);
        assertEquals(r.potentialPercent(), m.potentialPercent(), 1e-6);
        assertEquals(r.fixedInvested(), m.fixedInvested(), 1e-6);
        assertEquals(r.fixedGain(), m.fixedGain(), 1e-6);
        assertEquals(r.fixedPnlPercent(), m.fixedPnlPercent(), 1e-6);
    }
}
