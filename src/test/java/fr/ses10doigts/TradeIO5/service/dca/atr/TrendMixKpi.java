package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal.BuyKind;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal.SellKind;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator.Source;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * KPI de qualité d'une Trend (K1-K7b, K19, K20) et du Rainbow qu'elle pilote (K8-K11), définitions de
 * {@code docs/etudes/etude-bench-auto-trend-mix-nouvel-actif.md} §1. Calculateur PUR (séries en entrée, aucun I/O).
 * <p>
 * Direction effective {@code dir[i]} : dernière direction décisive (UP = +1, DOWN = -1) ; {@code RANGE} et {@code null}
 * conservent la direction précédente (RANGE n'est pas un switch). Un « switch » est un changement +1 ↔ -1.
 * Repères : pivots ZigZag « swing » (K1, K7, K20) et « majeur » (K5-K7b).
 */
final class TrendMixKpi {

    /** Segment de moins de 30 jours encadré par deux segments de l'autre sens = flip-flop (1 mois toléré). */
    static final int FLIPFLOP_DAYS = 30;
    /** Une bascule de front qui bouge de plus de 3 jours est « instable » (K6). */
    static final int STABLE_DAYS = 3;
    /** Une bascule SMA est « à temps » si elle tombe à ± 25 % de la jambe voisine d'un pivot swing (K20). */
    static final double SMA_TIMING_TOLERANCE = 0.25;
    /** Poids des fronts qui sont des ATH (K5a) et du Bull à tort (K4, K7b). */
    static final double ATH_WEIGHT = 2.0;
    static final double SAFETY_WEIGHT = 2.0;

    private TrendMixKpi() {
    }

    /** Un front majeur : bascule attendue après le pivot. {@code share} = part de la jambe déjà subie/passée à la bascule. */
    record Front(int pivot, boolean high, boolean ath, int switchDay, int lag, double share, boolean missed) {
    }

    /** Segment [start, end] de sens {@code dir} (jours inclus). */
    record FlipFlop(int start, int end, int dir) {
        int days() {
            return end - start + 1;
        }
    }

    /** Résultat des KPI de Trend sur la fenêtre [from, to]. */
    record Kpis(int from, int to, int[] dir, int switches, int swingPivots, List<Integer> segmentLengths,
                List<FlipFlop> bearInBull, List<FlipFlop> bullInBear, List<Front> fronts,
                double accord, double bullInDownDays, double bearInUpDays, double legDays,
                int smaSwitches, int smaMistimed) {

        double years() {
            return (to - from + 1) / 365.25;
        }

        /** K1. */
        double nervosity() {
            return swingPivots == 0 ? Double.NaN : (double) switches / swingPivots;
        }

        /** K2 : médiane des segments complets (tous les segments si aucun n'est complet). */
        double medianSegment() {
            if (segmentLengths.isEmpty()) {
                return Double.NaN;
            }
            double[] s = segmentLengths.stream().mapToDouble(Integer::doubleValue).sorted().toArray();
            return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
        }

        double bearInBullPerYear() {
            return bearInBull.size() / years();
        }

        double bearInBullDays() {
            return bearInBull.stream().mapToInt(FlipFlop::days).sum();
        }

        double bullInBearPerYear() {
            return bullInBear.size() / years();
        }

        double bullInBearDays() {
            return bullInBear.stream().mapToInt(FlipFlop::days).sum();
        }

        /** K4 pondéré ×2 (Bull à tort). */
        double bullInBearWeightedDays() {
            return SAFETY_WEIGHT * bullInBearDays();
        }

        /** K5a : retard moyen (jours) au sommet ; {@code athOnly} = ATH seuls / non-ATH seuls. */
        double topLag(boolean ath) {
            return mean(fronts.stream().filter(f -> f.high() && f.ath() == ath).mapToDouble(Front::lag).toArray());
        }

        /** K5a : part moyenne de la baisse déjà subie, ATH pondérés ×2. */
        double topShareWeighted() {
            double num = 0;
            double den = 0;
            for (Front f : fronts) {
                if (f.high()) {
                    double w = f.ath() ? ATH_WEIGHT : 1.0;
                    num += w * f.share();
                    den += w;
                }
            }
            return den == 0 ? Double.NaN : num / den;
        }

        double topShare(boolean ath) {
            return mean(fronts.stream().filter(f -> f.high() && f.ath() == ath).mapToDouble(Front::share).toArray());
        }

        /** K5b : retard moyen (jours) au creux. */
        double bottomLag() {
            return mean(fronts.stream().filter(f -> !f.high()).mapToDouble(Front::lag).toArray());
        }

        /** K5b : part moyenne de la jambe haussière déjà passée à la bascule UP. */
        double bottomShare() {
            return mean(fronts.stream().filter(f -> !f.high()).mapToDouble(Front::share).toArray());
        }

        /** K7b pondéré : (2 × jours Bull en baisse majeure + jours Bear en hausse majeure) / jours des jambes majeures. */
        double counterTrendWeighted() {
            return legDays == 0 ? Double.NaN : (SAFETY_WEIGHT * bullInDownDays + bearInUpDays) / legDays;
        }

        /** K20 : part des switchs dus à la SMA ({@code NaN} sans source). */
        double smaSwitchShare() {
            return switches == 0 || smaSwitches < 0 ? Double.NaN : (double) smaSwitches / switches;
        }

        /** K20 : part des switchs SMA qui tombent à contretemps. */
        double smaMistimedShare() {
            return smaSwitches <= 0 ? Double.NaN : (double) smaMistimed / smaSwitches;
        }
    }

    /**
     * @param regime  régimes par bougie (null = indéfini)
     * @param source  source du Mix par bougie, {@code null} si inconnue (régression seule / SMA seule / aléatoire)
     * @param swing   pivots swing de tout l'historique
     * @param major   pivots majeurs de tout l'historique
     * @param from    1re bougie de la fenêtre mesurée (commune à tous les candidats)
     */
    static Kpis compute(double[] close, TrendRegime[] regime, Source[] source, List<int[]> swing, List<int[]> major,
                        int from, int to) {
        int[] dir = directions(regime, to);

        List<int[]> segs = new ArrayList<>();
        int s = -1;
        for (int i = from; i <= to; i++) {
            if (dir[i] == 0) {
                continue;
            }
            if (s < 0) {
                s = i;
            } else if (dir[i] != dir[i - 1]) {
                segs.add(new int[]{s, i - 1, dir[i - 1]});
                s = i;
            }
        }
        if (s >= 0) {
            segs.add(new int[]{s, to, dir[to]});
        }
        int switches = Math.max(0, segs.size() - 1);
        List<Integer> lengths = new ArrayList<>();
        List<FlipFlop> bearInBull = new ArrayList<>();
        List<FlipFlop> bullInBear = new ArrayList<>();
        for (int k = 1; k < segs.size() - 1; k++) {
            int[] g = segs.get(k);
            int len = g[1] - g[0] + 1;
            lengths.add(len);
            if (len < FLIPFLOP_DAYS) {
                (g[2] < 0 ? bearInBull : bullInBear).add(new FlipFlop(g[0], g[1], g[2]));
            }
        }
        if (lengths.isEmpty()) {
            segs.forEach(g -> lengths.add(g[1] - g[0] + 1));
        }

        int swingInRange = (int) swing.stream().filter(p -> p[0] >= from && p[0] <= to).count();
        List<Front> fronts = fronts(close, dir, major, from, to);
        double accord = balancedAgreement(dir, swing, from, to);

        double bullInDown = 0;
        double bearInUp = 0;
        double legDays = 0;
        for (int k = 0; k + 1 < major.size(); k++) {
            int a = major.get(k)[0];
            int b = major.get(k + 1)[0];
            if (a < from || b > to) {
                continue;
            }
            boolean upLeg = major.get(k)[1] == ZigZag.LOW;
            for (int i = a + 1; i <= b; i++) {
                legDays++;
                if (upLeg && dir[i] < 0) {
                    bearInUp++;
                } else if (!upLeg && dir[i] > 0) {
                    bullInDown++;
                }
            }
        }

        int smaSwitches = -1;
        int smaMistimed = 0;
        if (source != null) {
            smaSwitches = 0;
            for (int k = 1; k < segs.size(); k++) {
                int day = segs.get(k)[0];
                if (source[day] == Source.SMA) {
                    smaSwitches++;
                    if (!nearPivot(swing, day, segs.get(k)[2] < 0 ? ZigZag.HIGH : ZigZag.LOW)) {
                        smaMistimed++;
                    }
                }
            }
        }
        return new Kpis(from, to, dir, switches, swingInRange, lengths, bearInBull, bullInBear, fronts,
                accord, bullInDown, bearInUp, legDays, smaSwitches, smaMistimed);
    }

    /** Direction effective par bougie (RANGE / null : on garde la précédente). */
    static int[] directions(TrendRegime[] regime, int to) {
        int[] dir = new int[regime.length];
        int cur = 0;
        for (int i = 0; i <= to; i++) {
            if (regime[i] == TrendRegime.UP) {
                cur = 1;
            } else if (regime[i] == TrendRegime.DOWN) {
                cur = -1;
            }
            dir[i] = cur;
        }
        return dir;
    }

    /** K5a / K5b : pour chaque front majeur de la fenêtre, 1er jour du bon sens à partir du pivot (jusqu'au pivot suivant). */
    private static List<Front> fronts(double[] close, int[] dir, List<int[]> major, int from, int to) {
        double[] athRun = new double[close.length];
        double mx = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < close.length; i++) {
            mx = Math.max(mx, close[i]);
            athRun[i] = mx;
        }
        List<Front> out = new ArrayList<>();
        for (int k = 0; k + 1 < major.size(); k++) {
            int p = major.get(k)[0];
            int next = major.get(k + 1)[0];
            if (p < from || next > to) {
                continue;
            }
            boolean high = major.get(k)[1] == ZigZag.HIGH;
            int target = high ? -1 : 1;
            int d = -1;
            for (int i = p; i <= next; i++) {
                if (dir[i] == target) {
                    d = i;
                    break;
                }
            }
            boolean missed = d < 0;
            if (missed) {
                d = next;
            }
            double leg = high ? close[p] - close[next] : close[next] - close[p];
            double done = high ? close[p] - close[d] : close[d] - close[p];
            double share = missed ? 1.0 : leg <= 0 ? 0 : Math.min(1.0, Math.max(0.0, done / leg));
            out.add(new Front(p, high, high && close[p] >= athRun[p], d, d - p, share, missed));
        }
        return out;
    }

    /**
     * K7 : accord équilibré (spec trend unifié §10.2) sur les jambes swing : (jours dans le bon sens − jours à contresens)
     * / jours, moyenné séparément sur les jambes haussières et baissières puis entre les deux.
     */
    private static double balancedAgreement(int[] dir, List<int[]> swing, int from, int to) {
        double upSum = 0;
        double upDays = 0;
        double downSum = 0;
        double downDays = 0;
        for (int k = 0; k + 1 < swing.size(); k++) {
            int a = swing.get(k)[0];
            int b = swing.get(k + 1)[0];
            if (a < from || b > to) {
                continue;
            }
            boolean upLeg = swing.get(k)[1] == ZigZag.LOW;
            for (int i = a + 1; i <= b; i++) {
                double v = dir[i] * (upLeg ? 1.0 : -1.0);
                if (upLeg) {
                    upSum += v;
                    upDays++;
                } else {
                    downSum += v;
                    downDays++;
                }
            }
        }
        if (upDays == 0 && downDays == 0) {
            return Double.NaN;
        }
        double up = upDays == 0 ? downSum / downDays : upSum / upDays;
        double down = downDays == 0 ? up : downSum / downDays;
        return (up + down) / 2;
    }

    /** {@code day} est à ± 25 % de la jambe voisine d'un pivot swing de ce type (haut pour une bascule DOWN). */
    private static boolean nearPivot(List<int[]> swing, int day, int type) {
        for (int k = 0; k < swing.size(); k++) {
            if (swing.get(k)[1] != type) {
                continue;
            }
            int p = swing.get(k)[0];
            int prevLen = k > 0 ? p - swing.get(k - 1)[0] : 0;
            int nextLen = k + 1 < swing.size() ? swing.get(k + 1)[0] - p : 0;
            if (day >= p - SMA_TIMING_TOLERANCE * prevLen && day <= p + SMA_TIMING_TOLERANCE * nextLen) {
                return true;
            }
        }
        return false;
    }

    /** K6 : part des fronts dont la bascule bouge d'au plus {@link #STABLE_DAYS} jours entre deux candidats. */
    static double frontStability(Kpis reference, Kpis other) {
        int n = Math.min(reference.fronts().size(), other.fronts().size());
        if (n == 0) {
            return Double.NaN;
        }
        int stable = 0;
        for (int k = 0; k < n; k++) {
            if (Math.abs(reference.fronts().get(k).switchDay() - other.fronts().get(k).switchDay()) <= STABLE_DAYS) {
                stable++;
            }
        }
        return (double) stable / n;
    }

    // ------------------------------------------------------------------ Rainbow piloté par la Trend (K8-K11)

    /**
     * K8-K11 d'un rejeu. Capital déployé plafonné au budget du DCA fixe comme {@code RainbowAtrRegimeBench#budgetScale}
     * (gain ramené à budget égal si on déploie plus). Valeurs en % du budget du DCA fixe ({@code fixedInvested}).
     *
     * @param scaledGain  gain total ramené à budget égal (× scale) ; {@code fixedGain} = gain du DCA fixe
     * @param excessPct   (gain total × scale − gain DCA fixe) / budget fixe (K8)
     * @param deployedPct capital investi / budget fixe
     * @param peakPct     pic de valeur de la position (quantité × clôture) / budget fixe (K9)
     * @param drawdownPct plus grande baisse du gain total depuis son plus haut / budget fixe (K10)
     * @param sells       jours de vente (K11)
     * @param rebuys      ventes suivies d'au moins un achat avant la vente suivante (K11)
     */
    record Rainbow(double totalGain, double scaledGain, double fixedGain, double excessPct, double deployedPct, double peakPct, double drawdownPct,
                   int sells, int rebuys, double years) {

        /** K11 : dégénéré si moins d'une vente et d'un rachat par an. */
        double minEventsPerYear() {
            return Math.min(sells, rebuys) / years;
        }
    }

    static Rainbow rainbow(List<DayRow> rows, double baseAmount) {
        double fixedInvested = baseAmount * rows.size();
        DayRow last = rows.getLast();
        double scale = last.invested() > fixedInvested ? fixedInvested / last.invested() : 1.0;
        double peak = 0;
        double peakGain = Double.NEGATIVE_INFINITY;
        double dd = 0;
        int sells = 0;
        int rebuys = 0;
        boolean awaitingRebuy = false;
        for (DayRow r : rows) {
            peak = Math.max(peak, r.position() * r.close());
            peakGain = Math.max(peakGain, r.totalGain());
            dd = Math.max(dd, peakGain - r.totalGain());
            if (r.buyKind() != BuyKind.NONE && awaitingRebuy) {
                rebuys++;
                awaitingRebuy = false;
            }
            if (r.sellKind() != SellKind.NONE) {
                sells++;
                awaitingRebuy = true;
            }
        }
        return new Rainbow(last.totalGain(), last.totalGain() * scale, last.fixedGain(), (last.totalGain() * scale - last.fixedGain()) / fixedInvested * 100,
                last.invested() / fixedInvested * 100, peak / fixedInvested * 100, dd / fixedInvested * 100,
                sells, rebuys, rows.size() / 365.25);
    }

    static double mean(double[] v) {
        return v.length == 0 ? Double.NaN : Arrays.stream(v).average().orElse(Double.NaN);
    }
}
