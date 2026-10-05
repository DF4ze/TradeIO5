package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainbowAtrReplayTest {

    private static RainbowAtrDataset series(long seed, int n) {
        Random rnd = new Random(seed);
        long[] t = new long[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double p = 100;
        for (int i = 0; i < n; i++) {
            double next = p * (1 + 0.0015 * Math.sin(i / 60.0) + 0.0008 + rnd.nextGaussian() * 0.025);
            t[i] = 86_400_000L * i;
            h[i] = Math.max(p, next) * (1 + Math.abs(rnd.nextGaussian()) * 0.01);
            l[i] = Math.min(p, next) * (1 - Math.abs(rnd.nextGaussian()) * 0.01);
            c[i] = next;
            p = next;
        }
        return new RainbowAtrDataset(t, h, l, c);
    }

    private static RainbowAtrParamSet[] btcSets() {
        return RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
    }

    @Test
    @DisplayName("Rejeu à jeu fixe == simulate (même jeu) : investi, vendu, réalisé, position")
    void fixedSetEqualsSimulate() {
        for (RainbowAtrParamSet set : btcSets()) {
            for (long seed = 1; seed <= 6; seed++) {
                RainbowAtrDataset ds = series(seed, 900);
                int start = RainbowAtrDataset.warmup(set.tuning().smaPeriod(), set.tuning().atrPeriod()) + 120;
                RainbowAtrResult sim = RainbowAtrEngine.simulate(ds, set.globals(), set.tuning(), start, ds.size() - 1);
                List<DayRow> rows = RainbowAtrReplay.run(ds, new RainbowAtrParamSet[]{set},
                        RainbowSetSelector.fixed(ds.size(), 0), start, ds.size() - 1);
                DayRow last = rows.getLast();
                String msg = set.name() + " seed=" + seed;
                assertEquals(sim.bars(), rows.size(), msg);
                assertEquals(sim.invested(), last.invested(), 1e-9, msg);
                assertEquals(sim.saleProceeds(), last.saleProceeds(), 1e-6, msg);
                assertEquals(sim.realizedGain(), last.realizedGain(), 1e-6, msg);
                assertEquals(sim.position(), last.position(), 1e-9, msg);
                assertEquals(sim.fixedGain(), last.fixedGain(), 1e-9, msg);
            }
        }
    }

    @Test
    @DisplayName("Changer de jeu conserve l'état : deux jeux identiques alternés == un seul jeu")
    void switchingKeepsState() {
        RainbowAtrParamSet a = btcSets()[0];
        RainbowAtrParamSet twin = new RainbowAtrParamSet("jumeau", a.tuning(), a.globals());
        RainbowAtrDataset ds = series(3, 900);
        int start = 200;
        int[] alternating = new int[ds.size()];
        for (int i = 0; i < alternating.length; i++) { alternating[i] = (i / 7) % 2; }
        List<DayRow> one = RainbowAtrReplay.run(ds, new RainbowAtrParamSet[]{a}, RainbowSetSelector.fixed(ds.size(), 0), start, ds.size() - 1);
        List<DayRow> two = RainbowAtrReplay.run(ds, new RainbowAtrParamSet[]{a, twin}, alternating, start, ds.size() - 1);
        assertEquals(one.size(), two.size());
        for (int i = 0; i < one.size(); i++) {
            assertEquals(one.get(i).position(), two.get(i).position(), 0.0, "jour " + i);
            assertEquals(one.get(i).realizedGain(), two.get(i).realizedGain(), 0.0, "jour " + i);
            assertEquals(one.get(i).buyArmed(), two.get(i).buyArmed(), "jour " + i);
        }
        assertTrue(one.getLast().invested() > 0);
    }

    @Test
    @DisplayName("Sélecteur : UP→Bull, DOWN→Bear, RANGE selon le mapping, Bear au démarrage")
    void selector() {
        TrendRegime[] r = {null, TrendRegime.RANGE, TrendRegime.UP, TrendRegime.RANGE, TrendRegime.DOWN, TrendRegime.RANGE};
        int bear = RainbowAtrPresets.BEAR;
        int bull = RainbowAtrPresets.BULL;
        assertArrayEquals(new int[]{bear, bear, bull, bull, bear, bear}, RainbowSetSelector.select(r, RangeMapping.KEEP_PREVIOUS));
        assertArrayEquals(new int[]{bear, bear, bull, bear, bear, bear}, RainbowSetSelector.select(r, RangeMapping.TO_BEAR));
        assertArrayEquals(new int[]{bear, bull, bull, bull, bear, bull}, RainbowSetSelector.select(r, RangeMapping.TO_BULL));
    }

    @Test
    @DisplayName("Presets : 2 jeux valides par actif (BTC, ETH, PAXG)")
    void presetsValid() {
        for (String asset : new String[]{"BTC", "ETH", "PAXG"}) {
            List<RainbowAtrParamSet> sets = RainbowAtrPresets.bearBull(asset);
            assertEquals(2, sets.size());
            sets.forEach(s -> assertTrue(s.tuning().isValid(), s.name()));
        }
    }
}
