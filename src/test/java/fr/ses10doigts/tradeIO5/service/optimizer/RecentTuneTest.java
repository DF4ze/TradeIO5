package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Forward;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.TPoint;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape D0 : dates T, aucune donnée postérieure à T, ordre des bornes ATR, preset inchangé = comparateur, garde-fou, perturbations, verdict, reproductibilité. */
class RecentTuneTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final LocalDate T = LocalDate.parse("2025-02-17");

    private static ScorerInputs inputs;
    private static ExecutorService pool;
    private static RecentTuneConfig cfg;
    private static RecentTune.Tuned tuned;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), 10_000.0, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        pool = Executors.newFixedThreadPool(2);
        cfg = RecentTuneConfig.defaults(END).withTrials(100).withRounds(1).withPerturbations(4, 12);
        tuned = RecentTune.tune(inputs, T, cfg, pool);
    }

    @AfterAll
    static void close() {
        pool.shutdown();
    }

    @Test
    @DisplayName("Dates T : tous les 2 mois dès 2020-11-17, derniers 6 mois complets (34 T jusqu'à 2026-05-17 pour une dernière bougie au 2026-11-17)")
    void dates() {
        List<LocalDate> dates = RecentTuneConfig.defaults(LocalDate.parse("2026-11-17")).dates();
        assertEquals(34, dates.size());
        assertEquals(LocalDate.parse("2020-11-17"), dates.getFirst());
        assertEquals(LocalDate.parse("2026-05-17"), dates.getLast());
        for (int i = 1; i < dates.size(); i++) {
            assertEquals(dates.get(i - 1).plusMonths(2), dates.get(i));
        }
        assertFalse(dates.getLast().plusMonths(6).isAfter(LocalDate.parse("2026-11-17")));
    }

    @Test
    @DisplayName("Aucune donnée postérieure à T dans le réglage de T : bougies futures empoisonnées ⇒ réglage identique, dataset coupé en T")
    void noDataAfterT() {
        ScorerInputs cut = RecentTune.truncated(inputs, T);
        RainbowAtrDataset ds = cut.assets().getFirst().template().ds();
        assertFalse(ReplayDays.dayOf(ds.time(ds.size() - 1)).isAfter(T));
        assertEquals(T, ReplayDays.dayOf(ds.time(ds.size() - 1)));
        RecentTune.Tuned poisoned = RecentTune.tune(poison(inputs, T), T, cfg, pool);
        assertNotNull(tuned);
        assertNotNull(poisoned);
        assertArrayEquals(tuned.tuned(), poisoned.tuned());
        assertEquals(tuned.searches(), poisoned.searches());
        assertEquals(tuned.tunedCycle(), poisoned.tunedCycle());
    }

    @Test
    @DisplayName("Reproductibilité : mêmes entrées ⇒ même réglage et mêmes essais, quel que soit le pool")
    void reproducible() {
        ExecutorService single = Executors.newFixedThreadPool(1);
        try {
            RecentTune.Tuned again = RecentTune.tune(inputs, T, cfg, single);
            assertArrayEquals(tuned.tuned(), again.tuned());
            assertEquals(tuned.searches(), again.searches());
            RecentTune.Perturbations a = RecentTune.perturbations(tuned, cfg);
            RecentTune.Perturbations b = RecentTune.perturbations(again, cfg);
            assertEquals(a.draws(), b.draws());
            assertEquals(a.sets().size(), b.sets().size());
            for (int i = 0; i < a.sets().size(); i++) {
                assertArrayEquals(a.sets().get(i), b.sets().get(i));
            }
        } finally {
            single.shutdown();
        }
    }

    @Test
    @DisplayName("Ordre des bornes ATR respecté tout en autorisant les zones X1 / X0,5 (bornes à 0 dans le preset)")
    void atrOrderAndZones() {
        RainbowAtrParamSet[] preset = tuned.preset();
        RainbowAtrParamSet bull = preset[RainbowAtrPresets.BULL];
        assertFalse(RecentTuneSpace.zoneX1(bull));
        assertFalse(RecentTuneSpace.zoneX05(bull));
        ParamSpace space = RecentTuneSpace.build(bull);
        assertEquals(SensitivityParam.ALL.size(), space.free().size());
        SobolSequence sobol = new SobolSequence(space.free().size(), 7L);
        int window = tuned.ev().template().windowMonths();
        boolean x1 = false;
        boolean x05 = false;
        for (int i = 0; i < 400; i++) {
            Candidate c = space.fromUnit(sobol.next(), space.startCandidate());
            RainbowAtrParamSet set = RecentTuneSpace.resolve(space, c, bull, window);
            assertNotNull(set, "jeu invalide pour l'Expert : " + c.key());
            var t = set.tuning();
            assertTrue(t.atrMultDown1() <= t.atrMultDown2() && t.atrMultUp1() <= t.atrMultUp2() && t.atrMultUp2() <= t.atrMultUp3());
            assertTrue(set.globals().athBuyMin() <= set.globals().athBuyMax());
            x1 |= RecentTuneSpace.zoneX1(set);
            x05 |= RecentTuneSpace.zoneX05(set);
        }
        assertTrue(x1 && x05, "les zones X1 / X0,5 doivent pouvoir s'activer");
        assertEquals(bull, RecentTuneSpace.resolve(space, space.startCandidate(), bull, window), "départ = preset à l'identique");
    }

    @Test
    @DisplayName("Preset inchangé : le départ de la recherche redonne le comparateur (cycle et gel) et le test en avançant Δ = 0")
    void unchangedPresetGivesComparator() {
        SensitivityEvaluator ev = tuned.ev();
        RainbowAtrParamSet[] preset = tuned.preset();
        for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
            ParamSpace space = RecentTuneSpace.build(preset[idx]);
            double expo = ev.cycle(preset, false).avgExposurePct();
            RecentTuneObjective objective = new RecentTuneObjective(ev, space, preset, idx, expo, 1.25);
            Evaluation e = objective.evaluate(space.startCandidate(), Fidelity.ALL_WINDOWS_COMPLETE, Scenario.BASE);
            assertFalse(e.vetoed());
            assertEquals(ev.frozen(preset, idx).totalGain(), e.score(), 1e-9);
            assertEquals(ev.cycle(preset, false), ((RecentTuneObjective.Measure) e.detail()).cycle());
        }
        RecentTune.Tuned same = new RecentTune.Tuned(T, tuned.from(), preset, preset.clone(), List.of(), ev, tuned.presetCycle(), tuned.presetCycle(),
                tuned.presetGel(), tuned.presetGel(), 0, 0);
        Forward fw = RecentTuneStudy.forward(inputs, same, new RecentTune.Perturbations(List.<RainbowAtrParamSet[]>of(preset), 1), 3, cfg, inputs.cash0());
        assertNotNull(fw);
        assertEquals(0.0, fw.deltaPts(), 1e-12);
        assertEquals(fw.preset(), fw.tuned());
        assertFalse(fw.beatsPreset());
        assertFalse(fw.beatsRandom());
        SensitivityEvaluator test = new SensitivityEvaluator(inputs.withEndDate(T.plusMonths(3)), 3, T);
        assertEquals(test.cycle(preset, false).pnlPct(), fw.preset().pnlPct(), 1e-9);
    }

    @Test
    @DisplayName("Garde-fou d'exposition : facteur 0 ⇒ tout jeu qui expose est vetoé (EXPOSURE_GUARD) ; facteur 1,25 ⇒ le preset passe")
    void guardRejects() {
        SensitivityEvaluator ev = tuned.ev();
        RainbowAtrParamSet[] preset = tuned.preset();
        int idx = RainbowAtrPresets.BEAR;
        ParamSpace space = RecentTuneSpace.build(preset[idx]);
        double expo = ev.cycle(preset, false).avgExposurePct();
        assertTrue(expo > 0);
        RecentTuneObjective strict = new RecentTuneObjective(ev, space, preset, idx, expo, 0.0);
        Evaluation e = strict.evaluate(space.startCandidate(), Fidelity.ALL_WINDOWS_COMPLETE, Scenario.BASE);
        assertTrue(e.vetoed());
        assertEquals(List.of(RecentTuneObjective.VETO_EXPOSURE_GUARD), e.vetoes());
        assertEquals(1, strict.guardRejections());
        RecentTuneObjective normal = new RecentTuneObjective(ev, space, preset, idx, expo, 1.25);
        assertFalse(normal.evaluate(space.startCandidate(), Fidelity.ALL_WINDOWS_COMPLETE, Scenario.BASE).vetoed());
    }

    @Test
    @DisplayName("Perturbations : mêmes réglages déplacés que le réglage, dans les bornes, garde-fou tenu, au plus N")
    void perturbations() {
        RecentTune.Perturbations p = RecentTune.perturbations(tuned, cfg);
        assertTrue(p.sets().size() <= cfg.perturbations());
        assertTrue(p.draws() <= cfg.maxPerturbationDraws());
        double presetExpo = tuned.presetCycle().avgExposurePct();
        for (RainbowAtrParamSet[] sets : p.sets()) {
            Reading r = tuned.ev().cycle(sets, false);
            assertFalse(BlockTuner.rejects(r.avgExposurePct(), presetExpo, cfg.exposureGuardFactor()));
            for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
                assertNull(SensitivityMap.violation(sets[idx], tuned.ev().template().windowMonths()));
            }
        }
        if (!tuned.changed()) {
            p.sets().forEach(sets -> assertArrayEquals(tuned.preset(), sets));
        }
    }

    @Test
    @DisplayName("Verdict fixé : PERSISTANT si Δ>0 sur ≥ 60 % des T, médiane > 0 et bat l'aléatoire sur ≥ 60 % ; sinon cause gain / bruit / phase")
    void verdictRule() {
        RecentTuneConfig c = RecentTuneConfig.defaults(LocalDate.parse("2026-11-17"));
        // 5 T : Δ > 0 sur 4, aléatoire battu sur 4
        RecentTuneReport.Verdict ok = RecentTuneStudy.verdict(points(6, new double[]{1, 2, 3, 4, -1}, new boolean[]{true, true, true, true, false}), c);
        assertEquals("RÉGLAGE_RÉCENT_PERSISTANT", ok.code());
        assertTrue(ok.verdictHorizon().pass());
        // gain : Δ > 0 sur 2 sur 5
        RecentTuneReport.Verdict gain = RecentTuneStudy.verdict(points(6, new double[]{1, 2, -3, -4, -1}, new boolean[]{true, true, false, false, false}), c);
        assertEquals("NON_PERSISTANT", gain.code());
        assertTrue(gain.verdictHorizon().causes().contains("gain"));
        // bruit : bat le preset 4/5 mais l'aléatoire 1/5
        RecentTuneReport.Verdict noise = RecentTuneStudy.verdict(points(6, new double[]{1, 2, 3, 4, -1}, new boolean[]{true, false, false, false, false}), c);
        assertEquals("NON_PERSISTANT", noise.code());
        assertFalse(noise.verdictHorizon().causes().contains("gain"));
        assertTrue(noise.verdictHorizon().causes().stream().anyMatch(s -> s.startsWith("bruit")));
        // 3 mois rapporté à part
        assertEquals(1, ok.others().size());
        assertEquals(3, ok.others().getFirst().horizonMonths());
        // événements effectifs ≈ n / 3 à 6 mois
        assertEquals(5 / 3.0, ok.verdictHorizon().effectiveEvents(), 1e-9);
    }

    @Test
    @DisplayName("Rapport reproductible à l'octet hors durées, JSON et markdown produits")
    void reportReproducible() {
        RecentTuneConfig one = cfg.withFirstT(T);
        RecentTuneConfig c = new RecentTuneConfig(T, T.plusMonths(6), 2, 24, List.of(3, 6), 1, 100, 1.25, 4, 12, one.seed(), 25.0, 10.0, 0.6, 40, 0.5);
        assertEquals(List.of(T), c.dates());
        RecentTuneReport a = RecentTuneStudy.run(inputs, c, pool, java.util.Map.of("BULL.sellFraction", "↑"), 10.0, 2);
        RecentTuneReport b = RecentTuneStudy.run(inputs, c, pool, java.util.Map.of("BULL.sellFraction", "↑"), 10.0, 2);
        assertEquals(a.toJsonWithoutTimings(), b.toJsonWithoutTimings());
        assertTrue(a.toMarkdown().contains("Verdict"));
        assertEquals(1, a.points().size());
        assertEquals(2, a.points().getFirst().forward().size());
        assertNotNull(a.latest());
    }

    // ------------------------------------------------------------------------------------------------------------ aides

    private static List<TPoint> points(int horizon, double[] deltas, boolean[] beatsRandom) {
        List<TPoint> out = new ArrayList<>();
        for (int i = 0; i < deltas.length; i++) {
            List<Forward> fws = new ArrayList<>();
            for (int h : List.of(3, 6)) {
                Line preset = new Line(0, 1.0, 5.0, -1.0, 1, 1);
                Line tunedLine = new Line(0, 1.0 + deltas[i], 5.0, -1.0, 1, 1);
                fws.add(new Forward(h, i % 2 == 0 ? "haussière" : "baissière", i % 2 == 0 ? 5.0 : -5.0, preset, tunedLine, preset, 1.0, true, deltas[i],
                        null, 0.0, 0.0, 0.0, 0.0, 20, 20, deltas[i] > 1e-9, beatsRandom[i], false));
            }
            out.add(new TPoint(LocalDate.parse("2021-01-17").plusMonths(2L * i), LocalDate.parse("2019-01-18"), true, "réglé", List.of(), List.of(), null, fws,
                    false, false, false, false, 0, 0));
        }
        return out;
    }

    /** Copie des entrées dont toutes les bougies postérieures à T sont fortement altérées (prix × 50, amplitude exagérée). */
    private static ScorerInputs poison(ScorerInputs in, LocalDate t) {
        ScorerInputs.AssetInput link = in.assets().getFirst();
        LinkSignalReplay.Config c = link.template();
        List<MarketData> candles = new ArrayList<>();
        for (MarketData m : c.candles()) {
            if (ReplayDays.dayOf(m.getTimestamp().toEpochMilli()).isAfter(t)) {
                candles.add(MarketData.builder().timeFrame(m.getTimeFrame()).timestamp(m.getTimestamp()).pair(m.getPair())
                        .open(m.getOpen().multiply(BigDecimal.valueOf(50))).high(m.getHigh().multiply(BigDecimal.valueOf(60)))
                        .low(m.getLow().multiply(BigDecimal.valueOf(40))).close(m.getClose().multiply(BigDecimal.valueOf(50)))
                        .volume(BigDecimal.ZERO).build());
            } else {
                candles.add(m);
            }
        }
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        LinkSignalReplay.Config poisoned = new LinkSignalReplay.Config(ds, candles, c.trend(), c.range(), c.sets(), 0, 0, candles.size() - 1,
                c.linkBaseAmount(), c.priceOverride(), c.athBefore(), c.rebootstrapDays(), c.windowMonths(), c.athCap(), c.fixedBaseAmount());
        return in.withAssets(List.of(link.withTemplate(poisoned)));
    }
}
