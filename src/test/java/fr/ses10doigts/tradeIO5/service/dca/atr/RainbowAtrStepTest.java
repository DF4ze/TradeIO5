package fr.ses10doigts.tradeIO5.service.dca.atr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Le rejeu {@link RainbowAtrEngine#simulate} est un fold de {@link RainbowAtrStrategy#step} : on refait ce fold
 * à la main avec l'API publique (automate moon recalculé bougie par bougie, ATH injecté par {@link AthReference},
 * position tenue ici) et on exige le même résultat que le moteur (qui, lui, reprend les drapeaux moon en cache).
 */
class RainbowAtrStepTest {

    private static RainbowAtrDataset randomSeries(long seed, int n) {
        Random rnd = new Random(seed);
        long[] t = new long[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        double p = 100;
        for (int i = 0; i < n; i++) {
            double drift = 0.0015 * Math.sin(i / 60.0) + 0.0008;
            double next = p * (1 + drift + rnd.nextGaussian() * 0.025);
            t[i] = 86_400_000L * i;
            h[i] = Math.max(p, next) * (1 + Math.abs(rnd.nextGaussian()) * 0.01);
            l[i] = Math.min(p, next) * (1 - Math.abs(rnd.nextGaussian()) * 0.01);
            c[i] = next;
            p = next;
        }
        return new RainbowAtrDataset(t, h, l, c);
    }

    private record Fold(double invested, double proceeds, double position, double realized, int buys, int sells,
                        int moonStops, RainbowAtrState state) { }

    private static Fold manualFold(RainbowAtrDataset ds, RainbowAtrGlobals g, RainbowAtrTuning t, int start, int end) {
        RainbowAtrState st = RainbowAtrState.initial();
        AthReference ath = AthReference.NONE;
        // état « persisté » : on rejoue l'historique AVANT la fenêtre pour amorcer ATH + automate moon
        double[] sma = ds.sma(t.smaPeriod());
        double[] atr = ds.atr(t.atrPeriod());
        ReferenceSizer sizer = new ReferenceSizer(BigDecimal.valueOf(g.baseAmount()));
        double pos = 0, cost = 0, invested = 0, proceeds = 0, realized = 0;
        int buys = 0, sells = 0, moonStops = 0;
        for (int i = 0; i <= end; i++) {
            double cl = ds.close(i);
            boolean live = i >= start;
            RainbowAtrStrategy.StepResult r = (Double.isNaN(sma[i]) || Double.isNaN(atr[i]) || !live)
                    ? RainbowAtrStrategy.stepInvalid(st, cl, ds.high(i), ath, g)
                    : RainbowAtrStrategy.step(st, RainbowAtrBand.of(cl, sma[i], atr[i], t), ds.high(i), ath, pos, t, g);
            st = r.state();
            ath = ath.observe(ds.high(i), ds.time(i));
            if (!live) {
                continue;
            }
            Sizer.Order o = sizer.size(r.signal(), BigDecimal.valueOf(cl), BigDecimal.valueOf(pos));
            double sellQ = o.sellQuantity().doubleValue();
            double buyA = o.buyAmount().doubleValue();
            if (sellQ > 0) {
                double c = cost * sellQ / pos;
                realized += sellQ * cl - c;
                cost -= c;
                pos -= sellQ;
                proceeds += sellQ * cl;
                if (r.signal().sellKind() == RainbowSignal.SellKind.MOON_STOP) { moonStops++; } else { sells++; }
            }
            if (buyA > 0) {
                pos += buyA / cl;
                cost += buyA;
                invested += buyA;
                buys++;
            }
        }
        return new Fold(invested, proceeds, pos, realized, buys, sells, moonStops, st);
    }

    @Test
    @DisplayName("simulate == fold manuel de step (tunings Bull/Bear, moon, ATH) sur séries aléatoires")
    void simulateIsFoldOfStep() {
        RainbowAtrGlobals g = RainbowAtrGlobals.pineDefault();
        RainbowAtrTuning[] tunings = {RainbowAtrTuning.pineCustomDefault(), RainbowAtrTuning.pineFinalCandidate()};
        int moons = 0;
        int sells = 0;
        for (long seed = 1; seed <= 12; seed++) {
            RainbowAtrDataset ds = randomSeries(seed, 900);
            for (RainbowAtrTuning t : tunings) {
                int start = RainbowAtrDataset.warmup(t.smaPeriod(), t.atrPeriod()) + 120; // moon/ATH déjà « en route »
                RainbowAtrResult sim = RainbowAtrEngine.simulate(ds, g, t, start, ds.size() - 1);
                Fold f = manualFold(ds, g, t, start, ds.size() - 1);
                String msg = "seed=" + seed + " tuning=" + t.smaPeriod() + "/" + t.atrPeriod();
                assertEquals(sim.invested(), f.invested(), 1e-9, msg);
                assertEquals(sim.saleProceeds(), f.proceeds(), 1e-6, msg);
                assertEquals(sim.position(), f.position(), 1e-9, msg);
                assertEquals(sim.realizedGain(), f.realized(), 1e-6, msg);
                assertEquals(sim.zoneBuys() + sim.triggeredBuys(), f.buys(), msg);
                assertEquals(sim.sells(), f.sells(), msg);
                assertEquals(sim.moonStops(), f.moonStops(), msg);
                assertEquals(sim.buyArmed(), f.state().buyArmed(), msg);
                assertEquals(sim.sellArmed(), f.state().sellArmed(), msg);
                assertEquals(sim.buyLocked(), f.state().buyLocked(), msg);
                assertEquals(sim.cooldownRemaining(), f.state().cooldown(), msg);
                assertEquals(sim.moonReserveQty(), f.state().reserveQty(), 1e-9, msg);
                moons += sim.moonEntries();
                sells += sim.sells();
            }
        }
        assertTrue(moons > 0 && sells > 0, "le jeu de séries doit exercer moon et ventes (moons=" + moons + ", ventes=" + sells + ")");
    }

    @Test
    @DisplayName("AthReference : veille vs jour, observe ne baisse jamais")
    void athReference() {
        AthReference a = AthReference.NONE;
        assertEquals(10.0, a.includingToday(10.0), 0.0);
        a = a.observe(10.0, 1L);
        assertEquals(12.0, a.includingToday(12.0), 0.0);
        assertEquals(10.0, a.includingToday(8.0), 0.0);
        assertEquals(a, a.observe(9.0, 2L));
        assertEquals(new AthReference(11.0, 3L), a.observe(11.0, 3L));
    }

    @Test
    @DisplayName("ReferenceSizer : base × facteurATH × multZone ; fraction × position")
    void referenceSizer() {
        ReferenceSizer s = new ReferenceSizer(BigDecimal.TEN);
        RainbowSignal buy = new RainbowSignal(true, RainbowAtrEngine.X2, RainbowSignal.BuyKind.ZONE, 2.0, 1.5,
                RainbowSignal.SellKind.NONE, 0, 1.0, false, false, false, false, 0.2, null, null);
        BigDecimal c = BigDecimal.valueOf(100);
        BigDecimal pos = BigDecimal.valueOf(5);
        assertEquals(0, new BigDecimal("30.0").compareTo(s.size(buy, c, pos).buyAmount()));
        assertEquals(0, s.size(buy, c, pos).sellQuantity().signum());
        RainbowSignal sell = new RainbowSignal(true, RainbowAtrEngine.EXTREME_HAUT, RainbowSignal.BuyKind.NONE, 0, 1.0,
                RainbowSignal.SellKind.SELL, 0.2, 1.0, false, false, false, false, 0.0, null, null);
        assertEquals(0, BigDecimal.ONE.compareTo(s.size(sell, c, pos).sellQuantity()));
        assertEquals(Sizer.Order.NONE, s.size(RainbowSignal.invalid(false, false), c, pos));
    }
}
