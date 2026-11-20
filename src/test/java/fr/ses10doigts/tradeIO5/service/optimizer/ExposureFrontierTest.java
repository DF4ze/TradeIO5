package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.AssetFrontier;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.ProfileLevel;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Range;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.Summary;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.BlockResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape C1 : ×1 = étape B' (preset et DCA égal), exposition croissante, niveau par profil depuis ModulationDefaults, reproductibilité. */
class ExposureFrontierTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final double CASH = 10_000.0;
    private static ScorerInputs inputs;
    private static FrontierConfig cfg;
    private static AssetFrontier report;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), CASH, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        cfg = FrontierConfig.defaults(END);
        report = ExposureFrontier.run(inputs, cfg, null);
    }

    @Test
    @DisplayName("×1 redonne exactement les chiffres de l'étape B' (preset et DCA égal), bloc par bloc et historique complet")
    void multiplierOneEqualsStepBPrime() {
        ParityReport.AssetReport b = ExposureParity.run(inputs, cfg.parity(), null);
        List<Range> ranges = new java.util.ArrayList<>(report.blocks());
        ranges.add(report.full());
        List<BlockResult> refs = new java.util.ArrayList<>(b.blocks());
        refs.add(b.full());
        assertEquals(refs.size(), ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            BlockResult ref = refs.get(i);
            Level one = ranges.get(i).levels().stream().filter(l -> l.multiplier() == 1.0).findFirst().orElseThrow();
            assertEquals(ref.valid(), one.valid());
            if (!ref.valid()) {
                continue;
            }
            assertEquals(ref.preset(), one.preset());
            assertEquals(ref.equalDca(), one.equalDca());
            assertEquals(ref.calib(), one.calib());
            assertEquals(ref.band().gainP80(), one.band().gainP80());
            assertEquals(ref.realized(), one.realized(), 1e-9);
            assertEquals(ref.latent(), one.latent(), 1e-9);
        }
    }

    @Test
    @DisplayName("L'exposition croît avec le multiplicateur jusqu'à saturation (jamais de baisse notable), et le levier ne change pas le preset à ×1")
    void exposureGrowsUntilSaturation() {
        List<Summary> s = report.summaries();
        assertTrue(s.size() >= cfg.multipliers().size());
        for (int i = 1; i < s.size(); i++) {
            assertTrue(s.get(i).exposurePct() >= s.get(i - 1).exposurePct() - 0.5,
                    "exposition en baisse entre ×" + s.get(i - 1).multiplier() + " et ×" + s.get(i).multiplier());
        }
        assertTrue(s.getLast().exposurePct() > s.getFirst().exposurePct() + 1.0, "l'exposition doit monter avec le multiplicateur");
        // ×1 : base du lien inchangée (aucun montant imposé)
        assertEquals(null, ExposureFrontier.linkBase(1.0, 100.0));
        assertEquals(200.0, ExposureFrontier.linkBase(2.0, 100.0), 1e-12);
    }

    @Test
    @DisplayName("Un montant de base imposé change la taille des achats du preset ; ×1 explicite = base du lien")
    void linkBaseScalesPreset() {
        TuneBlocks.Block recent = TuneBlocks.build(END, 24, 4).getFirst();
        SensitivityEvaluator ev = new SensitivityEvaluator(inputs.withEndDate(recent.to()), 24, recent.from());
        RainbowAtrParamSet[] sets = ev.template().sets();
        double ref = ev.referenceBase(sets);
        Parity base = ev.parity(sets, false, 0, null);
        Parity same = ev.parity(sets, false, 0, null, ref);
        Parity big = ev.parity(sets, false, 0, null, 4 * ref);
        assertNotNull(big);
        assertEquals(base.preset().pnlPct(), same.preset().pnlPct(), 1e-9);
        assertTrue(big.preset().avgExposurePct() > base.preset().avgExposurePct());
    }

    @Test
    @DisplayName("Niveau par profil : plafonds lus dans ModulationDefaults (jamais codés en dur), exposition interpolée entre les deux points qui encadrent")
    void profilesUseModulationDefaults() {
        Map<StartingProfile, Double> caps = FrontierConfig.profileCaps();
        assertEquals(ModulationDefaults.limit(ModulationDefaults.DRAWDOWN_CAP_PRUDENT_PCT).defaultValue().doubleValue(), caps.get(StartingProfile.PRUDENT));
        assertEquals(ModulationDefaults.limit(ModulationDefaults.DRAWDOWN_CAP_BALANCED_PCT).defaultValue().doubleValue(), caps.get(StartingProfile.BALANCED));
        assertEquals(ModulationDefaults.limit(ModulationDefaults.DRAWDOWN_CAP_DYNAMIC_PCT).defaultValue().doubleValue(), caps.get(StartingProfile.DYNAMIC));
        assertEquals(ModulationDefaults.limit(ModulationDefaults.DRAWDOWN_CAP_FULL_PCT).defaultValue().doubleValue(), caps.get(StartingProfile.FULL));
        assertEquals(4, report.profiles().size());
        for (ProfileLevel p : report.profiles()) {
            assertEquals(caps.get(StartingProfile.valueOf(p.profile())), p.capPct(), 1e-12);
            if ("INTERPOLE".equals(p.status())) {
                Summary lo = null;
                Summary hi = null;
                for (Summary s : report.summaries()) {
                    if (s.worstDrawdown() >= p.capPct()) {
                        lo = s;
                    } else if (hi == null) {
                        hi = s;
                    }
                }
                assertNotNull(lo);
                assertNotNull(hi);
                assertTrue(p.exposurePct() >= lo.exposurePct() - 1e-9 && p.exposurePct() <= hi.exposurePct() + 1e-9);
                assertEquals(p.capPct(), p.worstDrawdown(), 1e-12);
            }
        }
        // profil plus prudent => exposition maximale plus basse (plafonds -15 > -25 > -40 > -60)
        List<ProfileLevel> ps = report.profiles().stream().filter(p -> p.exposurePct() != null).toList();
        for (int i = 1; i < ps.size(); i++) {
            assertTrue(ps.get(i).exposurePct() >= ps.get(i - 1).exposurePct() - 1e-9);
        }
    }

    @Test
    @DisplayName("Règle de niveau : 3 blocs sur 4, médiane Δ gain > 0, aucun drawdown pire de plus de 5 pts que le DCA égal")
    void levelRule() {
        ParityConfig pc = cfg.parity();
        assertTrue(ExposureFrontier.passes(3, 0.1, -4.9, pc));
        assertTrue(!ExposureFrontier.passes(2, 5.0, 0.0, pc));
        assertTrue(!ExposureFrontier.passes(4, 0.0, 0.0, pc));
        assertTrue(!ExposureFrontier.passes(4, 1.0, -5.1, pc));
    }

    @Test
    @DisplayName("Reproductibilité : deux runs donnent le même JSON (hors durées)")
    void reproducible() {
        AssetFrontier again = ExposureFrontier.run(inputs, cfg, null);
        assertEquals(new FrontierReport("d", cfg, List.of(report), List.of()).toJsonWithoutTimings(),
                new FrontierReport("d", cfg, List.of(again), List.of()).toJsonWithoutTimings());
    }
}
