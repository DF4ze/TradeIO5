package fr.ses10doigts.tradeIO5.service.dca.atr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Rejeu jour par jour du Rainbow ATR avec un JEU COMPLET de paramètres choisi à chaque bougie (Trend → Bull/Bear,
 * ou un jeu fixe) : fold de {@link RainbowAtrStrategy#step} + {@link ReferenceSizer} (base × facteurATH × multZone,
 * sans plafond) + comptabilité de position, en conservant l'état de la machine au changement de jeu. Sert à
 * comparer le Java au pine (rendu jour par jour) ; produit une {@link DayRow} par bougie rejouée.
 * <p>
 * Comme le pine sur la plage visible : machine d'états à froid à {@code startIdx}, mais ATH et automate To the moon
 * issus de TOUT l'historique du dataset (l'automate moon est rejoué de la 1re bougie à {@code startIdx - 1} avec le
 * jeu actif de chaque bougie).
 * {@link RainbowAtrEngine#simulate} reste le chemin rapide du bench (un seul jeu de « globals »).
 */
public final class RainbowAtrReplay {

    /** Tout ce qu'il faut pour redessiner/auditer une journée. {@code set} = index dans le tableau de jeux. */
    public record DayRow(
            int index, long timeMillis, double open, double high, double low, double close,
            int set, RainbowAtrBand band, double ath, double athDistance, double buyAthFactor, double sellAthFactor,
            boolean moonMode, boolean moonEntry,
            RainbowSignal.BuyKind buyKind, double multZone, double buyAmount, double buyQuantity,
            RainbowSignal.SellKind sellKind, double sellFraction, double sellQuantity, double sellProceeds,
            boolean buyArmedNow, boolean sellArmedNow,
            boolean buyArmed, boolean sellArmed, boolean buyLocked, int cooldown, double reserveQty,
            double position, double costBasis, double invested, double saleProceeds, double realizedGain,
            double fixedGain,
            boolean active,
            double valuationPrice,
            double fixedInvested
    ) {
        /** Valorisation au prix de valorisation (= clôture tant qu'aucune barre n'est inactive, cf. {@link RainbowAtrReplay#run}). */
        public double potentialGain() { return position * valuationPrice - costBasis; }

        public double totalGain() { return saleProceeds + position * valuationPrice - invested; }
    }

    private RainbowAtrReplay() {
    }

    /**
     * Encodage d'une bougie INACTIVE (hors régime) dans {@code setOfBar} : {@code inactive(k)} = jeu {@code k} dont l'état
     * continue de vivre sans nouvelle activité ({@link RainbowAtrStrategy#step}, {@code allowNewEntries=false}) et sans
     * DCA fixe de référence. Une ligne est émise quand même ({@link DayRow#active()} = {@code false}).
     */
    public static int inactive(int setIndex) {
        return ~setIndex;
    }

    public static boolean isInactive(int setOfBarValue) {
        return setOfBarValue < 0;
    }

    /** Index de jeu d'une valeur de {@code setOfBar}, active ou non. */
    public static int setIndex(int setOfBarValue) {
        return setOfBarValue < 0 ? ~setOfBarValue : setOfBarValue;
    }

    /**
     * @param sets     jeux disponibles
     * @param setOfBar index de jeu actif par bougie du dataset (longueur = {@code ds.size()}) ; {@link #inactive} pour une
     *                 bougie hors régime
     */
    public static List<DayRow> run(RainbowAtrDataset ds, RainbowAtrParamSet[] sets, int[] setOfBar,
                                   int startIdx, int endIdx) {
        return runFull(ds, sets, setOfBar, startIdx, endIdx).rows();
    }

    /** Lignes du rejeu + état de la machine après la dernière bougie (amorçage de l'exécution quotidienne). */
    public record Result(List<DayRow> rows, RainbowAtrState finalState) {
        /** Position virtuelle après la dernière bougie rejouée (0 si aucune ligne). */
        public double finalPosition() {
            return rows.isEmpty() ? 0.0 : rows.getLast().position();
        }
    }

    /**
     * Comme {@link #run} mais renvoie aussi l'état final. Si {@code endIdx < startIdx} : aucune ligne, état initial
     * dont l'automate moon est avancé jusqu'à {@code startIdx - 1} (amorçage sans fenêtre à rejouer).
     */
    public static Result runFull(RainbowAtrDataset ds, RainbowAtrParamSet[] sets, int[] setOfBar,
                                 int startIdx, int endIdx) {
        if (setOfBar.length != ds.size()) {
            throw new IllegalArgumentException("setOfBar doit couvrir tout le dataset");
        }
        ReferenceSizer sizer = new ReferenceSizer(BigDecimal.valueOf(sets[0].globals().baseAmount()));
        double base = sets[0].globals().baseAmount();

        RainbowMoon.State moon = RainbowMoon.State.INACTIVE;
        for (int j = 0; j < startIdx; j++) {
            RainbowAtrGlobals gj = sets[setIndex(setOfBar[j])].globals();
            moon = RainbowMoon.advance(moon, gj.moonOn(), gj.moonTrailingStopPct(), ds.close(j), ds.high(j),
                    j > 0 ? ds.ath(j - 1) : Double.NaN).next();
        }
        RainbowAtrState st = RainbowAtrState.initial().withMoon(moon);
        AthReference ath = startIdx > 0 ? new AthReference(ds.ath(startIdx - 1), ds.time(startIdx - 1)) : AthReference.NONE;

        double pos = 0, costBasis = 0, realized = 0, invested = 0, saleProceeds = 0;
        double fixedQty = 0, fixedInvested = 0;
        // Prix de valorisation : suit la clôture en proportion (rendement identique) sur les barres actives, reste plat sur les barres
        // inactives (pas de rattrapage à la reprise). Ordres, bag, PnL, exposition et DCA fixe y sont évalués ; exactement la clôture
        // si aucune barre n'est inactive.
        double vp = Double.NaN, prevClose = Double.NaN;
        List<DayRow> rows = new ArrayList<>();

        for (int i = startIdx; i <= endIdx; i++) {
            boolean active = !isInactive(setOfBar[i]);
            int k = setIndex(setOfBar[i]);
            RainbowAtrParamSet set = sets[k];
            RainbowAtrTuning t = set.tuning();
            RainbowAtrGlobals g = set.globals();
            double sma = ds.sma(t.smaPeriod())[i];
            double atr = ds.atr(t.atrPeriod())[i];
            double cl = ds.close(i);
            double hi = ds.high(i);
            vp = Double.isNaN(vp) ? cl : active ? vp * cl / prevClose : vp;
            prevClose = cl;
            if (Double.isNaN(sma) || Double.isNaN(atr)) {
                RainbowAtrStrategy.StepResult r = RainbowAtrStrategy.stepInvalid(st, cl, hi, ath, g, active);
                st = r.state();
                ath = ath.observe(hi, ds.time(i));
                continue;
            }
            double athToday = ath.includingToday(hi);
            RainbowAtrBand band = RainbowAtrBand.of(cl, sma, atr, t);
            RainbowAtrStrategy.StepResult r = RainbowAtrStrategy.step(st, band, hi, ath, pos, t, g, active);
            ath = ath.observe(hi, ds.time(i));
            st = r.state();
            RainbowSignal sig = r.signal();

            boolean trade = sig.sellKind() != RainbowSignal.SellKind.NONE || sig.buyKind() != RainbowSignal.BuyKind.NONE;
            Sizer.Order order = trade ? sizer.size(sig, BigDecimal.valueOf(vp), BigDecimal.valueOf(pos)) : Sizer.Order.NONE;

            double sellQty = order.sellQuantity().doubleValue();
            double sellProc = 0;
            if (sig.sellKind() != RainbowSignal.SellKind.NONE) {
                sellProc = sellQty * vp;
                double cost = costBasis * sellQty / pos;
                realized += sellProc - cost;
                costBasis -= cost;
                pos -= sellQty;
                saleProceeds += sellProc;
            }
            double buyAmt = order.buyAmount().doubleValue();
            double buyQty = 0;
            if (sig.buyKind() != RainbowSignal.BuyKind.NONE) {
                buyQty = buyAmt / vp;
                pos += buyQty;
                costBasis += buyAmt;
                invested += buyAmt;
            }
            if (active) {
                fixedQty += base / vp;
                fixedInvested += base;
            }

            rows.add(new DayRow(i, ds.time(i), ds.open(i), hi, ds.low(i), cl, k, band, athToday, sig.athDistance(),
                    sig.buyAthFactor(), sig.sellAthFactor(), sig.moonMode(), sig.moonEntry(),
                    sig.buyKind(), sig.multZone(), buyAmt, buyQty,
                    sig.sellKind(), sig.sellFraction(), sellQty, sellProc,
                    sig.buyArmedNow(), sig.sellArmedNow(),
                    st.buyArmed(), st.sellArmed(), st.buyLocked(), st.cooldown(), st.reserveQty(),
                    pos, costBasis, invested, saleProceeds, realized, fixedQty * vp - fixedInvested, active, vp, fixedInvested));
        }
        return new Result(rows, st);
    }
}
