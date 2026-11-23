package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AssetCut;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Point;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.AssetRobustness;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.Rule1;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.WalletResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Étape E2 : taille de ré-entrée respectée (série synthétique), ablation d'un bloc = recalcul sans ce bloc, décalages de départ sans donnée future,
 * wallet à un lien = évaluateur d'un actif (avec coupure), reproductibilité (série contre pool), décalage 0 = E1.
 */
class SwitchRobustnessTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final List<Double> GRID = List.of(0.5, 1.0, 2.0, 4.0, 8.0);
    private static ScorerInputs btc;
    private static ScorerInputs eth;

    @BeforeAll
    static void load() throws IOException {
        var l1 = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        var l2 = PresetOptimizerTestSupport.link("ETH", PresetOptimizerTestSupport.csv("ETH", Integer.MAX_VALUE));
        l2 = new ScorerInputs.AssetInput(new LiveSlot(2L, 2L, "ETH", 1L, 2, 100.0, null), l2.template(), "TEST", l2.meanMultiplier(), l2.referenceBase());
        btc = ScorerInputs.of(List.of(l1), 2_484.0, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        eth = ScorerInputs.of(List.of(l2), 2_425.0, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
    }

    private static SwitchRobustnessConfig small() {
        SwitchCutConfig cut = new SwitchCutConfig(ParityConfig.defaults(END), GRID, 1.0, 20.0, 3);
        return new SwitchRobustnessConfig(cut, List.of(0.0, 10.0), List.of(10.0), List.of(10.0), List.of(0, 30, 60), 0.8, 3, 0.5, 1.0, List.of(1.0, 2.0, 4.0), 25.0, 10.0);
    }

    // ------------------------------------------------------------------ taille de ré-entrée (série synthétique)

    /** Cycles de 800 jours, amplitude ±60 % en log : phases Bull et Bear nettes. */
    private static List<MarketData> synthetic(int days) {
        List<MarketData> out = new ArrayList<>();
        Instant t0 = LocalDate.parse("2018-01-01").atStartOfDay().toInstant(ZoneOffset.UTC);
        double prev = 100.0;
        for (int i = 0; i < days; i++) {
            double close = 100.0 * Math.exp(0.6 * Math.sin(2 * Math.PI * i / 800.0));
            out.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(t0.plusSeconds(86_400L * i)).pair("BTCUSDC").open(BigDecimal.valueOf(prev))
                    .high(BigDecimal.valueOf(Math.max(prev, close) * 1.01)).low(BigDecimal.valueOf(Math.min(prev, close) * 0.99)).close(BigDecimal.valueOf(close))
                    .volume(BigDecimal.ZERO).build());
            prev = close;
        }
        return out;
    }

    private static MultiLinkReplayEngine.Result replay(ScorerInputs in, RainbowAtrParamSet[] sets, MultiLinkReplayEngine.SwitchCut cut) {
        var link = in.assets().getFirst();
        LinkSignalReplay.Config t = link.template();
        RainbowAtrDataset ds = t.ds();
        SensitivityEvaluator ev = new SensitivityEvaluator(in, 24);
        int start = ReplayDays.firstIndexOnOrAfter(ds, ev.firstReplayableDay(sets));
        int end = ReplayDays.lastIndexOnOrBefore(ds, in.endDate());
        var engineLink = new MultiLinkReplayEngine.Link(link.slot(), new LinkSignalReplay.Config(ds, t.candles(), t.trend(), t.range(), sets, start, start, end, 1.0,
                Map.of(), Map.of(), Set.of(), t.windowMonths(), null, null), 0.0);
        return MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(engineLink), in.cash0(), in.baseSettings(), in.limits(), Map.of(), Map.of(), Map.of(),
                Map.of(), OptimizerCosts.simplified(Set.of(link.slot().assetSymbol())), null, null, cut));
    }

    @Test
    @DisplayName("Taille de ré-entrée respectée : chaque rachat = taille % du capital initial (série synthétique, cash non borné)")
    void reentrySizeIsRespected() {
        List<MarketData> candles = synthetic(2400);
        var link = PresetOptimizerTestSupport.link("BTC", candles);
        double cash0 = 1_000_000.0;
        LocalDate end = candles.getLast().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        ScorerInputs in = ScorerInputs.of(List.of(link), cash0, end, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        RainbowAtrParamSet[] sets = SwitchCutStudy.withoutAth(new SensitivityEvaluator(in, 24).template().sets());
        double previousTotal = -1;
        for (double pct : new double[]{5.0, 10.0, 20.0}) {
            var r = replay(in, sets, new MultiLinkReplayEngine.SwitchCut(1.0, pct));
            List<MultiLinkReplayEngine.CutEvent> buys = r.cuts().stream().filter(e -> "BUY".equals(e.kind())).toList();
            assertTrue(r.cuts().stream().anyMatch(e -> "SELL".equals(e.kind())), "au moins un switch Bull → Bear synthétique");
            assertTrue(!buys.isEmpty(), "au moins une ré-entrée synthétique");
            double expected = pct / 100.0 * cash0;
            for (MultiLinkReplayEngine.CutEvent b : buys) {
                assertEquals(expected, -b.cashDelta(), expected * 0.01, "rachat de " + pct + " % du capital initial");
            }
            double total = buys.stream().mapToDouble(b -> -b.cashDelta()).sum();
            assertTrue(total > previousTotal, "le total racheté croît avec la taille");
            previousTotal = total;
        }
        var none = replay(in, sets, new MultiLinkReplayEngine.SwitchCut(1.0, 0.0));
        assertTrue(none.cuts().stream().noneMatch(e -> "BUY".equals(e.kind())), "taille 0 : aucune ré-entrée");
    }

    // ------------------------------------------------------------------ ablation d'un bloc

    private static Level level(double m, double dd, double... gains) {
        List<Point> ps = new ArrayList<>();
        for (double g : gains) {
            ps.add(new Point(g, dd, 50.0));
        }
        return new Level(m, ps, ps, 0, 0, 0.0);
    }

    /** Deux niveaux qui encadrent tous les plafonds ; la coupure ajoute {@code delta[b]} au gain du pur sur le bloc b, aux deux niveaux. */
    private static AssetCut synthetic(double[] delta) {
        double[] pure1 = {10, 10, 10, 10};
        double[] pure2 = {20, 20, 20, 20};
        List<Point> p1 = new ArrayList<>();
        List<Point> p2 = new ArrayList<>();
        List<Point> c1 = new ArrayList<>();
        List<Point> c2 = new ArrayList<>();
        for (int b = 0; b < 4; b++) {
            p1.add(new Point(pure1[b], -10, 50));
            p2.add(new Point(pure2[b], -70, 50));
            c1.add(new Point(pure1[b] + delta[b], -10, 50));
            c2.add(new Point(pure2[b] + delta[b], -70, 50));
        }
        List<Level> levels = List.of(new Level(1, p1, c1, 0, 0, 0.0), new Level(2, p2, c2, 0, 0, 0.0));
        return new AssetCut("X", 1000, 1, List.of("b0", "b1", "b2", "b3"), levels, List.of(), 0, "n/d", "", 0, 0);
    }

    @Test
    @DisplayName("Ablation : retirer un bloc = recalculer sans ce bloc ; le bloc qui renverse le signe est nommé")
    void ablationRecomputesWithoutTheBlock() {
        SwitchRobustnessConfig cfg = small();
        AssetCut fragile = synthetic(new double[]{50, 2, -1, -5});
        Rule1 r = SwitchRobustness.rule1(fragile, cfg);
        assertEquals("FRAGILE", r.verdict());
        assertEquals("b0", r.flippingBlock());
        for (var cap : r.caps()) {
            assertEquals(0.5, cap.fullMedianDelta(), 1e-9, "4 blocs : médiane de {50, 2, -1, -5}");
            assertEquals(-1.0, cap.ablations().get(0).medianDelta(), 1e-9, "sans b0 : médiane de {2, -1, -5}");
            assertEquals(-1.0, cap.ablations().get(1).medianDelta(), 1e-9, "sans b1 : médiane de {50, -1, -5}");
            assertEquals(2.0, cap.ablations().get(2).medianDelta(), 1e-9, "sans b2 : médiane de {50, 2, -5}");
            assertEquals(2.0, cap.ablations().get(3).medianDelta(), 1e-9, "sans b3 : médiane de {50, 2, -1}");
        }
        List<Level> sub = SwitchRobustness.drop(fragile.levels(), 0);
        assertEquals(3, sub.getFirst().pure().size());
        assertEquals(3, sub.getFirst().cut().size());
        assertEquals(2.0, sub.getFirst().cut().getFirst().gainPct() - sub.getFirst().pure().getFirst().gainPct(), 1e-9);
        AssetCut robust = synthetic(new double[]{10, 5, 4, 3});
        Rule1 ok = SwitchRobustness.rule1(robust, cfg);
        assertEquals("ROBUSTE", ok.verdict());
        assertEquals(4, ok.capsAllPositive());
        assertNull(ok.flippingBlock());
    }

    // ------------------------------------------------------------------ décalages de départ

    @Test
    @DisplayName("Décalages de départ : le départ avance de N jours et rien après la fin n'est lu (bougies tronquées = mêmes chiffres)")
    void shiftedStartsUseNoFutureData() throws IOException {
        List<MarketData> all = PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE);
        List<MarketData> cut = PresetOptimizerTestSupport.csv("BTC", 3000);
        LocalDate end = cut.getLast().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        ScorerInputs full = ScorerInputs.of(List.of(PresetOptimizerTestSupport.link("BTC", all)), 2_484.0, end, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        ScorerInputs trunc = ScorerInputs.of(List.of(PresetOptimizerTestSupport.link("BTC", cut)), 2_484.0, end, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        SensitivityEvaluator a = new SensitivityEvaluator(full, 24);
        SensitivityEvaluator b = new SensitivityEvaluator(trunc, 24);
        RainbowAtrParamSet[] sets = SwitchCutStudy.withoutAth(a.template().sets());
        var cutCfg = new MultiLinkReplayEngine.SwitchCut(1.0, 10.0);
        var zero = a.parityCut(sets, false, 0, null, null, null, cutCfg).parity();
        for (int shift : new int[]{0, 20, 60}) {
            var x = a.parityCut(sets, false, shift, null, null, null, cutCfg);
            var y = b.parityCut(SwitchCutStudy.withoutAth(b.template().sets()), false, shift, null, null, null, cutCfg);
            assertNotNull(x);
            assertNotNull(y);
            assertEquals(zero.from().plusDays(shift), x.parity().from(), "le départ avance de " + shift + " j");
            assertEquals(zero.to(), x.parity().to(), "la fin ne bouge pas");
            assertEquals(x.parity().preset().finalValue(), y.parity().preset().finalValue(), 1e-9, "aucune donnée après la fin (décalage " + shift + ")");
            assertEquals(x.parity().preset().maxDrawdownPct(), y.parity().preset().maxDrawdownPct(), 1e-9);
        }
    }

    @Test
    @DisplayName("Décalage 0 de la bande de bruit = run E1 (mêmes niveaux, mêmes verdicts par plafond)")
    void shiftZeroEqualsE1() {
        SwitchRobustnessConfig cfg = small();
        double size = 10.0;
        AssetCut e1 = SwitchCutStudy.run(btc, cfg.cut().withReentry(size));
        var su = SwitchRobustness.setup(btc, cfg.cut().parity());
        var cut = new MultiLinkReplayEngine.SwitchCut(1.0, size);
        List<Level> mine = SwitchRobustness.assemble(GRID, SwitchRobustness.lines(su, GRID, 0, null), SwitchRobustness.lines(su, GRID, 0, cut));
        assertEquals(e1.levels().size(), mine.size());
        var caps = SwitchRobustness.caps(mine, cfg.cut().parity());
        for (int i = 0; i < caps.size(); i++) {
            var a = e1.caps().get(i);
            assertEquals(a.verdict(), caps.get(i).verdict());
            if (a.medianDelta() == null) {
                assertNull(caps.get(i).medianDelta());
            } else {
                assertEquals(a.medianDelta(), caps.get(i).medianDelta(), 1e-9);
            }
        }
    }

    // ------------------------------------------------------------------ wallet à un lien

    @Test
    @DisplayName("Wallet à un lien avec coupure = évaluateur d'un actif avec coupure (mêmes chiffres, mêmes ordres)")
    void oneLinkWalletEqualsSingleWithCut() {
        var a = btc.assets().getFirst();
        RainbowAtrParamSet[] off = SwitchCutStudy.withoutAth(new SensitivityEvaluator(btc, 24).template().sets());
        ScorerInputs w = btc.withAssets(List.of(a.withTemplate(a.template().withSets(off)))).withEndDate(END);
        SensitivityEvaluator ev = new SensitivityEvaluator(w, 24);
        var cut = new MultiLinkReplayEngine.SwitchCut(1.0, 20.0);
        var single = ev.parityCut(off, true, 0, null, 3.0, null, cut);
        WalletEvaluator.CutLine wal = new WalletEvaluator(w, null).lineCut(new double[]{3.0}, cut);
        assertNotNull(wal);
        Line s = single.parity().preset();
        assertEquals(s.maxDrawdownPct(), wal.line().maxDrawdownPct(), 1e-9);
        assertEquals(s.pnlPct(), wal.line().pnlPct(), 1e-9);
        assertEquals(s.avgExposurePct(), wal.line().avgExposurePct(), 1e-9);
        assertEquals(single.cutSells(), wal.cutSells());
        assertEquals(single.cutBuys(), wal.cutBuys());
        assertTrue(wal.cutSells() > 0);
        assertEquals(new WalletEvaluator(w, null).line(new double[]{3.0}).pnlPct(), new WalletEvaluator(w, null).lineCut(new double[]{3.0}, null).line().pnlPct(), 1e-12);
    }

    // ------------------------------------------------------------------ reproductibilité

    private static String report(ExecutorService pool) {
        SwitchRobustnessConfig cfg = small();
        List<AssetRobustness> assets = List.of(SwitchRobustness.runAsset(btc, cfg, pool), SwitchRobustness.runAsset(eth, cfg, pool));
        WalletResult wallet = SwitchRobustness.runWallet(btc, eth, cfg, pool);
        return new SwitchRobustnessReport(SwitchRobustnessReport.DEFINITION, cfg, assets, wallet, SwitchRobustness.verdicts(assets, wallet, cfg), List.of(), List.of(), 0L)
                .toJsonWithoutTimings();
    }

    @Test
    @DisplayName("Reproductibilité : série et pool de 4 threads donnent le même rapport à l'octet près (hors durées)")
    void reproducible() {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            String serial = report(null);
            assertEquals(serial, report(pool));
            assertEquals(serial, report(null));
        } finally {
            pool.shutdown();
        }
    }
}
