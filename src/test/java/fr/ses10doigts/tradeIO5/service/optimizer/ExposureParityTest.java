package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.AssetReport;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.BlockResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape B' : calibrage à 0,5 pt, montant inchangé si déjà calé, décalages déterministes, comparateurs de l'étape A, verdict. */
class ExposureParityTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static ScorerInputs inputs;
    private static SensitivityEvaluator ev;
    private static RainbowAtrParamSet[] sets;
    private static final double CASH = 10_000.0;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), CASH, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        TuneBlocks.Block recent = TuneBlocks.build(END, 24, 4).getFirst();
        ev = new SensitivityEvaluator(inputs.withEndDate(recent.to()), 24, recent.from());
        sets = ev.template().sets();
    }

    @Test
    @DisplayName("Calibrage : l'exposition moyenne du DCA égale celle du preset à 0,5 pt près")
    void calibrationReachesTarget() {
        Parity first = ev.parity(sets, false, 0, null);
        assertNotNull(first);
        ExposureParity.Calibration c = ExposureParity.calibrate(ev, sets, false, first, CASH, 0.5, 40);
        assertTrue(c.calib().reached(), "tolérance non tenue : écart " + c.calib().errorPts());
        assertEquals(first.preset().avgExposurePct(), c.parity().dca().avgExposurePct(), 0.5);
        assertTrue(c.base() > 0 && c.base() <= CASH);
    }

    @Test
    @DisplayName("Un DCA calé sur sa propre exposition garde son montant : cible = exposition du DCA habituel ⇒ base du lien")
    void alreadyCalibratedKeepsAmount() {
        Parity usual = ev.parity(sets, false, 0, null);
        double usualBase = ev.template().linkBaseAmount() != null ? ev.template().linkBaseAmount() : sets[0].globals().baseAmount();
        // le DCA habituel sert de « preset » fictif : on cale le DCA sur SA propre exposition (même rejeu, cible = exposition du DCA)
        Parity self = new Parity(usual.from(), usual.to(), usual.dca(), usual.realized(), usual.latent(), usual.dca());
        ExposureParity.Calibration c = ExposureParity.calibrate(ev, sets, false, self, CASH, 0.5, 40);
        Parity atBase = ev.parity(sets, false, 0, usualBase);
        assertEquals(usual.dca().avgExposurePct(), atBase.dca().avgExposurePct(), 1e-9);
        assertEquals(usual.dca().pnlPct(), atBase.dca().pnlPct(), 1e-9);
        // le montant calé redonne la même exposition (à la tolérance) et, rejoué, le même gain que le montant d'origine à cette exposition
        assertEquals(usual.dca().avgExposurePct(), c.parity().dca().avgExposurePct(), 0.5);
        double ratio = c.base() / usualBase;
        assertTrue(ratio > 0.9 && ratio < 1.1, "montant calé " + c.base() + " ≠ montant d'origine " + usualBase);
    }

    @Test
    @DisplayName("Le montant du DCA ne change rien au preset ; le preset et le DCA habituel redonnent la lecture « cycle » de l'étape A")
    void presetAndUsualDcaMatchStepA() {
        Reading cycle = ev.cycle(sets, false);
        Parity p = ev.parity(sets, false, 0, null);
        Parity other = ev.parity(sets, false, 0, 37.0);
        assertEquals(cycle.pnlPct(), p.preset().pnlPct(), 1e-9);
        assertEquals(100.0 * cycle.fixedPnl() / CASH, p.dca().pnlPct(), 1e-9);
        assertEquals(cycle.avgExposurePct(), p.preset().avgExposurePct(), 1e-9);
        assertEquals(cycle.maxDrawdownPct(), p.preset().maxDrawdownPct(), 1e-9);
        assertEquals(cycle.realized(), p.realized(), 1e-9);
        assertEquals(p.preset(), other.preset());
    }

    @Test
    @DisplayName("Décalages de début : réguliers, 0 et 60 inclus, déterministes ; rapport identique d'un run à l'autre")
    void shiftsAreDeterministic() {
        List<Integer> s = ExposureParity.shifts(20, 60);
        assertEquals(20, s.size());
        assertEquals(0, s.getFirst());
        assertEquals(60, s.getLast());
        assertEquals(s, ExposureParity.shifts(20, 60));
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.get(i) > s.get(i - 1) && s.get(i) - s.get(i - 1) <= 4);
        }
        ParityConfig cfg = ParityConfig.defaults(END);
        AssetReport a = ExposureParity.run(inputs, cfg, null);
        AssetReport b = ExposureParity.run(inputs, cfg, null);
        assertEquals(new ParityReport("d", cfg, List.of(a), List.of()).toJsonWithoutTimings(),
                new ParityReport("d", cfg, List.of(b), List.of()).toJsonWithoutTimings());
        BlockResult recent = a.blocks().getFirst();
        assertTrue(recent.valid());
        assertEquals(20, recent.band().gains().size());
        assertEquals(recent.equalDca().gainPct(), recent.band().gains().getFirst(), 1e-9);
    }

    @Test
    @DisplayName("Percentile : interpolation linéaire, null sans valeur")
    void percentile() {
        assertEquals(3.0, ExposureParity.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.5), 1e-12);
        assertEquals(4.2, ExposureParity.percentile(List.of(1.0, 2.0, 3.0, 4.0, 5.0), 0.8), 1e-12);
        assertNull(ExposureParity.percentile(List.of(), 0.8));
    }

    private static BlockResult block(int i, double dGain, boolean ddOk, boolean gainWin, boolean gainOut, boolean pass, String failure) {
        return new BlockResult(i, "b" + i, END, END, true, "", null, 0, 0, null, null, null, null, null, dGain, null, ddOk, gainWin, gainOut, false,
                false, pass, failure);
    }

    @Test
    @DisplayName("Verdict : TIMING_UTILE à 3 blocs sur 4 et médiane Δ > 0 ; sinon NON_DEMONTRE avec le côté de l'échec")
    void verdict() {
        ParityConfig cfg = ParityConfig.defaults(END);
        var ok = ExposureParity.verdict(List.of(block(0, 1, true, true, true, true, ""), block(1, 2, true, true, true, true, ""),
                block(2, 0.5, true, true, true, true, ""), block(3, -1, true, false, false, false, "gain")), cfg);
        assertEquals("TIMING_UTILE", ok.decision());
        var noise = ExposureParity.verdict(List.of(block(0, 1, true, true, false, false, "bruit"), block(1, 2, true, true, false, false, "bruit"),
                block(2, 0.5, true, true, true, true, ""), block(3, 1, true, true, true, true, "")), cfg);
        assertEquals("NON_DEMONTRE", noise.decision());
        assertEquals("bruit", noise.failureSide());
        var dd = ExposureParity.verdict(List.of(block(0, 1, false, true, true, false, "drawdown"), block(1, 2, false, true, true, false, "drawdown"),
                block(2, 3, true, true, true, true, ""), block(3, -1, true, false, false, false, "gain")), cfg);
        assertEquals("drawdown", dd.failureSide());
        var median = ExposureParity.verdict(List.of(block(0, -5, true, true, true, true, ""), block(1, -4, true, true, true, true, ""),
                block(2, -3, true, true, true, true, ""), block(3, 9, true, true, true, true, "")), cfg);
        assertEquals("NON_DEMONTRE", median.decision());
        assertTrue(median.failureSide().startsWith("gain"));
    }
}
