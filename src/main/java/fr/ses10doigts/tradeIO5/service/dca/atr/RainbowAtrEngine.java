package fr.ses10doigts.tradeIO5.service.dca.atr;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Rejeu du Rainbow DCA "bornes ATR" v3 (ATH + To the moon) — port Java fidèle de
 * {@code tools/pine/rainbow_dca_v4_atr_moon.pine}, SOURCE DE VÉRITÉ : en cas d'écart, c'est le pine qui a
 * raison. Moteur pur (double, sans Spring), ~µs par bougie, conçu pour être appelé des centaines de milliers de
 * fois par un bench.
 * <p>
 * {@link #simulate} est un FOLD sur les bougies : bornes L1 ({@link RainbowAtrBand}) → machine L2
 * ({@link RainbowAtrStrategy#step}, ordre exact des opérations documenté dans sa javadoc) → signal décomposé
 * ({@link RainbowSignal}) → {@link ReferenceSizer} (base × facteurATH × multZone, sans plafond) → comptabilité de
 * la position (coût de revient, réalisé, investi). L'exécution quotidienne appelle le même {@code step}.
 * <p>
 * ATH : de la série (depuis la 1re bougie, cf. {@link RainbowAtrDataset}) injecté via {@link AthReference} ;
 * facteurs ATH neutralisés (1.0) pendant le mode moon.
 * <p>
 * Pilotage par régime : {@code tunings[k]} avec {@code regimeOfBar[i] = k} ; l'état (armements, cooldown,
 * position) traverse les changements de régime. Un seul tuning = pine classique.
 */
public final class RainbowAtrEngine {

    public static final int EXTREME_BAS = 0;
    public static final int X2 = 1;
    public static final int X1 = 2;
    public static final int X0_5 = 3;
    public static final int NO_BUY = 4;
    public static final int EXTREME_HAUT = 5;

    private RainbowAtrEngine() {
    }

    public static RainbowAtrResult simulate(
            RainbowAtrDataset ds, RainbowAtrGlobals g, RainbowAtrTuning tuning, int startIdx, int endIdx
    ) {
        return simulate(ds, g, new RainbowAtrTuning[]{tuning}, null, startIdx, endIdx, false);
    }

    public static RainbowAtrResult simulate(
            RainbowAtrDataset ds, RainbowAtrGlobals g, RainbowAtrTuning[] tunings, int[] regimeOfBar,
            int startIdx, int endIdx, boolean trace
    ) {
        int nT = tunings.length;
        double[][] smaArr = new double[nT][];
        double[][] atrArr = new double[nT][];
        for (int k = 0; k < nT; k++) {
            smaArr[k] = ds.sma(tunings[k].smaPeriod());
            atrArr[k] = ds.atr(tunings[k].atrPeriod());
        }
        RainbowAtrDataset.MoonFlags moon = ds.moon(g.moonOn(), g.moonTrailingStopPct());
        ReferenceSizer sizer = new ReferenceSizer(BigDecimal.valueOf(g.baseAmount()));
        List<RainbowAtrResult.Event> events = trace ? new ArrayList<>() : null;

        // l'automate moon et l'ATH ont une mémoire : on les reprend à la veille de la fenêtre rejouée
        RainbowAtrState st = RainbowAtrState.initial().withMoon(moon.stateAfter(startIdx - 1));
        AthReference ath = startIdx > 0 ? new AthReference(ds.ath(startIdx - 1), ds.time(startIdx - 1)) : AthReference.NONE;

        double pos = 0, costBasis = 0, realized = 0, invested = 0, saleProceeds = 0;
        double fixedQty = 0, fixedInvested = 0;
        int zoneBuys = 0, triggeredBuys = 0, sells = 0, moonEntries = 0, moonStops = 0, bars = 0;
        int lastZone = -1;
        double lastSma = Double.NaN, lastAtr = Double.NaN, lastBuyFac = 1, lastSellFac = 1, lastAthD = 0;
        boolean lastMoon = false;
        double lastPrice = Double.NaN;

        for (int i = startIdx; i <= endIdx; i++) {
            int k = regimeOfBar == null ? 0 : regimeOfBar[i];
            RainbowAtrTuning t = tunings[k];
            double sma = smaArr[k][i];
            double atr = atrArr[k][i];
            double cl = ds.close(i);
            RainbowMoon.Step ms = new RainbowMoon.Step(moon.stateAfter(i), moon.entry()[i], moon.exit()[i]);
            double athToday = ath.includingToday(ds.high(i));
            ath = ath.observe(ds.high(i), ds.time(i));
            if (Double.isNaN(sma) || Double.isNaN(atr)) {
                st = st.afterInvalidBar(ms.next(), cl);
                continue;
            }

            RainbowAtrStrategy.StepResult r = RainbowAtrStrategy.stepWithMoon(
                    st, RainbowAtrBand.of(cl, sma, atr, t), ms, athToday, pos, t, g);
            st = r.state();
            RainbowSignal sig = r.signal();
            // le Sizer (BigDecimal) n'est sollicité que les jours d'ordre ; la comptabilité du rejeu reste en double
            boolean trade = sig.sellKind() != RainbowSignal.SellKind.NONE || sig.buyKind() != RainbowSignal.BuyKind.NONE;
            Sizer.Order order = trade ? sizer.size(sig, BigDecimal.valueOf(cl), BigDecimal.valueOf(pos)) : Sizer.Order.NONE;

            if (sig.moonEntry()) {
                moonEntries++;
                if (trace) { events.add(new RainbowAtrResult.Event(i, "MOON_ENTRY", 0, 0)); }
            }
            if (sig.sellKind() != RainbowSignal.SellKind.NONE) {
                double qty = order.sellQuantity().doubleValue();
                double proceeds = qty * cl;
                double cost = costBasis * qty / pos;
                realized += proceeds - cost;
                costBasis -= cost;
                pos -= qty;
                saleProceeds += proceeds;
                boolean moonStop = sig.sellKind() == RainbowSignal.SellKind.MOON_STOP;
                if (moonStop) { moonStops++; } else { sells++; }
                if (trace) { events.add(new RainbowAtrResult.Event(i, moonStop ? "MOON_STOP" : "SELL", qty, proceeds)); }
            } else if (sig.sellArmedNow() && trace) {
                events.add(new RainbowAtrResult.Event(i, "SELL_ARM", 0, 0));
            }
            if (sig.buyArmedNow() && trace) {
                events.add(new RainbowAtrResult.Event(i, "BUY_ARM", 0, 0));
            }
            if (sig.buyKind() != RainbowSignal.BuyKind.NONE) {
                double inv = order.buyAmount().doubleValue();
                pos += inv / cl;
                costBasis += inv;
                invested += inv;
                boolean triggered = sig.buyKind() == RainbowSignal.BuyKind.TRIGGERED;
                if (triggered) { triggeredBuys++; } else { zoneBuys++; }
                if (trace) { events.add(new RainbowAtrResult.Event(i, triggered ? "BUY_TRIGGERED" : "BUY_ZONE", inv / cl, inv)); }
            }

            // DCA fixe de référence
            fixedQty += g.baseAmount() / cl;
            fixedInvested += g.baseAmount();

            bars++;
            lastZone = sig.zone(); lastSma = sma; lastAtr = atr; lastMoon = sig.moonMode();
            lastBuyFac = sig.buyAthFactor(); lastSellFac = sig.sellAthFactor(); lastAthD = sig.athDistance();
            lastPrice = cl;
        }

        return new RainbowAtrResult(invested, saleProceeds, pos * lastPrice, realized, costBasis, pos, st.reserveQty(),
                fixedQty, fixedInvested, lastPrice,
                zoneBuys, triggeredBuys, sells, moonEntries, moonStops, bars,
                lastZone, lastSma, lastAtr, lastMoon, st.buyArmed(), st.sellArmed(), st.buyLocked(), st.cooldown(),
                lastBuyFac, lastSellFac, lastAthD, events == null ? List.of() : events);
    }
}
