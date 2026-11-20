package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Window;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.AssetProduction;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.Measure;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.ProfileProduction;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.WalletBand;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.WalletProduction;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.WalletProfile;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionStudy.FSearch;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionStudy.Rig;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Étape C4 : le capital de C3 redonne C3 (taux de base → niveau), la configuration A = C3, le wallet à un lien = l'évaluateur d'un actif,
 * le facteur f rend le wallet conforme, reproductibilité (série contre pool). Configuration réduite : même code que le run réel.
 */
class ExposureProductionTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final double CASH_BTC = 2_484.0;
    private static final double CASH_ETH = 2_425.0;
    private static final List<Double> GRID = List.of(0.5, 1.0, 2.0, 4.0, 8.0, 16.0);
    private static ScorerInputs btc;
    private static ScorerInputs eth;
    private static ExposureProductionConfig cfg;
    private static ExecutorService pool;
    private static AssetProduction btcRes;
    private static AssetProduction ethRes;
    private static WalletProduction wallet;

    @BeforeAll
    static void load() throws IOException {
        var l1 = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        var l2 = PresetOptimizerTestSupport.link("ETH", PresetOptimizerTestSupport.csv("ETH", Integer.MAX_VALUE));
        l2 = new ScorerInputs.AssetInput(new LiveSlot(2L, 2L, "ETH", 1L, 2, 100.0, null), l2.template(), "TEST", l2.meanMultiplier(), l2.referenceBase());
        btc = ScorerInputs.of(List.of(l1), CASH_BTC, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        eth = ScorerInputs.of(List.of(l2), CASH_ETH, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        cfg = reduced(4);
        pool = Executors.newFixedThreadPool(4);
        btcRes = ExposureProductionStudy.runAsset(btc, cfg, pool);
        ethRes = ExposureProductionStudy.runAsset(eth, cfg, pool);
        wallet = ExposureProductionStudy.runWallet(btc, eth, btcRes, ethRes, cfg, pool);
    }

    private static ExposureProductionConfig reduced(int fIterations) {
        ExposureCalibrationConfig d = ExposureCalibrationConfig.defaults(END);
        ExposureCalibrationConfig cal = new ExposureCalibrationConfig(d.parity(), GRID, 24, 180, 72, 12, 12, 12, List.of(1.0), 0.9, d.bearFrom(), d.bearTo(), 90);
        return new ExposureProductionConfig(cal, 1.1, 1.0, List.of(1_000.0, 10_000.0), 90, 1.0, 2.0, 1.0, 2.0, 0.5, fIterations);
    }

    @AfterAll
    static void close() {
        pool.shutdown();
    }

    @Test
    @DisplayName("Capital de C3 : taux de base x capital = base du niveau ; la mesure redonne celle de C3 (constantLine) sur chaque plage")
    void c3CapitalGivesC3() {
        ExposureCalibrationConfig cal = cfg.calibration();
        SensitivityEvaluator head = new SensitivityEvaluator(btc.withEndDate(END), 24);
        RainbowAtrParamSet[] sets = head.template().sets();
        double ref = head.referenceBase(sets);
        for (ProfileProduction p : btcRes.profiles()) {
            double m = p.level().multiplier();
            // taux -> base : identité
            assertEquals(m * ref, p.level().baseRate() * CASH_BTC, 1e-9);
            assertEquals(m * ref, p.invariance().runs().getFirst().base(), 1e-9);
            // pire cas direct (C3) : fenêtres + historique, avec la constante au niveau m
            double worst = 0.0;
            for (Window w : ExposureCalibrationStudy.windowsFor(btc, cal)) {
                SensitivityEvaluator ev = new SensitivityEvaluator(btc.withEndDate(w.to()), 24, w.from());
                worst = Math.min(worst, ExposurePolicyStudy.constantLine(ev, sets, false, m, ref).maxDrawdownPct());
            }
            worst = Math.min(worst, ExposurePolicyStudy.constantLine(head, sets, true, m, ref).maxDrawdownPct());
            assertEquals(worst, p.a().measure().worstDdPct(), 1e-9, "configuration A = C3, profil " + p.profile());
            // gain médian des blocs de C3
            List<Double> gains = new java.util.ArrayList<>();
            for (Block b : TuneBlocks.build(END, 24, 4)) {
                SensitivityEvaluator ev = new SensitivityEvaluator(btc.withEndDate(b.to()), 24, b.from());
                gains.add(ExposurePolicyStudy.constantLine(ev, sets, false, m, ref).pnlPct());
            }
            assertEquals(ExposurePolicyStudy.median(gains), p.a().measure().medianGainPct(), 1e-9);
        }
    }

    @Test
    @DisplayName("Wallet à un lien = évaluateur d'un actif (même moteur, mêmes chiffres)")
    void oneLinkWalletEqualsSingle() {
        ScorerInputs w = btc.withEndDate(END);
        SensitivityEvaluator ev = new SensitivityEvaluator(w, 24);
        RainbowAtrParamSet[] sets = ev.template().sets();
        Line single = ev.parity(sets, true, 0, null, 3.0).preset();
        Line wal = new WalletEvaluator(w, null).line(new double[]{3.0});
        assertNotNull(wal);
        assertEquals(single.maxDrawdownPct(), wal.maxDrawdownPct(), 1e-9);
        assertEquals(single.pnlPct(), wal.pnlPct(), 1e-9);
        assertEquals(single.avgExposurePct(), wal.avgExposurePct(), 1e-9);
    }

    @Test
    @DisplayName("Facteur de réduction f : le wallet tient le plafond à f, f = 1 si déjà conforme (recherche à une dimension)")
    void reductionFactorMakesWalletConform() {
        for (WalletProfile p : wallet.profiles()) {
            for (WalletBand b : List.of(p.neutral(), p.profileBand())) {
                assertTrue(b.f() >= 0.0 && b.f() <= 1.0);
                assertTrue(b.holdsAtOne() ? b.f() == 1.0 : b.f() < 1.0);
                assertTrue(b.holds() || b.f() == 0.0, "wallet non conforme à f = " + b.f() + " (" + p.profile() + " " + b.band() + ")");
                assertEquals(b.f() * p.rateBtc() * wallet.cash() * wallet.share(), b.baseBtc(), 1e-9);
                assertEquals(b.f() * p.rateEth() * wallet.cash() * wallet.share(), b.baseEth(), 1e-9);
            }
        }
    }

    @Test
    @DisplayName("Dichotomie de f : monotone, résultat conforme, plus grand f conforme à la résolution près ; f = 1 sans recherche si conforme")
    void searchFIsMonotoneAndTight() {
        double cap = -25.0;
        java.util.function.DoubleFunction<Measure> at = f -> new Measure(-60.0 * f, "x", -1.0, 1.0, 1.0, 1.0, 1);
        FSearch s = ExposureProductionStudy.searchF(at, cap, 8);
        assertTrue(s.atF().worstDdPct() >= cap);
        assertEquals(cap / -60.0, s.f(), 1.0 / 256.0 + 1e-12);
        assertTrue(s.f() <= cap / -60.0 + 1e-12);
        assertEquals(1.0, ExposureProductionStudy.searchF(f -> new Measure(-10.0 * f, "x", 0, 0, 0, 0, 1), cap, 8).f(), 0.0);
        assertEquals(0, ExposureProductionStudy.searchF(f -> new Measure(-10.0 * f, "x", 0, 0, 0, 0, 1), cap, 8).iterations());
    }

    @Test
    @DisplayName("Règle d'invariance fixée avant lecture : OK ssi écart DD <= 1 pt et écart gain <= 2 pts pour chaque capital")
    void invarianceRuleIsApplied() {
        for (AssetProduction a : List.of(btcRes, ethRes)) {
            for (ProfileProduction p : a.profiles()) {
                double ref = p.invariance().runs().getFirst().measure().worstDdPct();
                double refGain = p.invariance().runs().getFirst().measure().medianGainPct();
                boolean ok = p.invariance().runs().stream().skip(1).allMatch(r -> Math.abs(r.measure().worstDdPct() - ref) <= 1.0 + 1e-9
                        && Math.abs(r.measure().medianGainPct() - refGain) <= 2.0 + 1e-9);
                assertEquals(ok, p.invariance().ok(), a.asset() + " " + p.profile());
            }
        }
    }

    @Test
    @DisplayName("Reproductibilité : série (sans pool) et pool donnent le même rapport")
    void reproducible() {
        ExposureProductionConfig c = reduced(3);
        AssetProduction serial = ExposureProductionStudy.runAsset(btc, c, null);
        AssetProduction parallel = ExposureProductionStudy.runAsset(btc, c, pool);
        ExposureProductionReport a = new ExposureProductionReport("d", c, List.of(serial), null, List.of());
        ExposureProductionReport b = new ExposureProductionReport("d", c, List.of(parallel), null, List.of());
        assertEquals(a.toJsonWithoutTimings(), b.toJsonWithoutTimings());
        Rig rig = ExposureProductionStudy.walletRig(new ScorerInputs(List.of(btc.assets().getFirst(), eth.assets().getFirst()), 4909.0, END,
                btc.baseSettings(), btc.limits(), btc.profile(), btc.windows(), btc.dispersionPenalty(), btc.maxFinalStake(), btc.completeCosts()),
                c.calibration(), ExposureCalibrationStudy.windows(END, 24, 180, WalletEvaluator.firstReplayableDay(new ScorerInputs(
                        List.of(btc.assets().getFirst(), eth.assets().getFirst()), 4909.0, END, btc.baseSettings(), btc.limits(), btc.profile(),
                        btc.windows(), btc.dispersionPenalty(), btc.maxFinalStake(), btc.completeCosts()))), TuneBlocks.build(END, 24, 4));
        double[] bases = {1.0, 1.0};
        assertEquals(ExposureProductionStudy.measure(rig, bases, null), ExposureProductionStudy.measure(rig, bases, pool));
    }
}
