package fr.ses10doigts.tradeIO5.service.dca.atr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ZigZag sur clôtures (repères de marché des benchs de Trend, mesure a posteriori, non causale) : un pivot est confirmé
 * quand le cours retourne d'au moins {@code threshold} (fraction) depuis l'extrême. Seuil adaptatif :
 * {@code c × ATR% médian de l'actif}, avec c calé pour que BTC donne ≈ 20 % (« swing ») et ≈ 35 % (« majeur »).
 */
final class ZigZag {

    /** Pivot bas / haut (valeur de {@code int[]{index, type}}). */
    static final int LOW = -1;
    static final int HIGH = 1;

    /** Période d'ATR de la volatilité de référence. */
    static final int ATR_PERIOD = 14;
    /** BTC : ATR% médian (14 j) = 4,38 % ⇒ 4,5 × = 19,7 % (swing) ; 8,0 × = 35,0 % (majeur). */
    static final double C_SWING = 4.5;
    static final double C_MAJOR = 8.0;

    private ZigZag() {
    }

    /** ATR% (ATR/clôture) médian de l'actif, sur les bougies où l'ATR est défini. */
    static double medianAtrPct(RainbowAtrDataset ds) {
        double[] atr = ds.atr(ATR_PERIOD);
        double[] v = new double[ds.size()];
        int k = 0;
        for (int i = 0; i < v.length; i++) {
            if (!Double.isNaN(atr[i])) {
                v[k++] = atr[i] / ds.close(i);
            }
        }
        double[] s = Arrays.copyOf(v, k);
        Arrays.sort(s);
        return k == 0 ? Double.NaN : k % 2 == 1 ? s[k / 2] : (s[k / 2 - 1] + s[k / 2]) / 2;
    }

    /** Pivots sur [si, ei] : {index, +1 haut / -1 bas}. Le dernier extrême, non confirmé, n'est pas listé. */
    static List<int[]> pivots(double[] c, int si, int ei, double threshold) {
        List<int[]> p = new ArrayList<>();
        int ext = si;
        boolean up = true;
        boolean decided = false;
        int hi = si;
        int lo = si;
        for (int i = si; i <= ei; i++) {
            if (!decided) {
                if (c[i] > c[hi]) { hi = i; }
                if (c[i] < c[lo]) { lo = i; }
                if (c[i] >= c[lo] * (1 + threshold) && lo < i) {
                    p.add(new int[]{lo, LOW});
                    up = true;
                    ext = i;
                    decided = true;
                } else if (c[i] <= c[hi] * (1 - threshold) && hi < i) {
                    p.add(new int[]{hi, HIGH});
                    up = false;
                    ext = i;
                    decided = true;
                }
                continue;
            }
            if (up) {
                if (c[i] > c[ext]) {
                    ext = i;
                } else if (c[i] <= c[ext] * (1 - threshold)) {
                    p.add(new int[]{ext, HIGH});
                    up = false;
                    ext = i;
                }
            } else {
                if (c[i] < c[ext]) {
                    ext = i;
                } else if (c[i] >= c[ext] * (1 + threshold)) {
                    p.add(new int[]{ext, LOW});
                    up = true;
                    ext = i;
                }
            }
        }
        return p;
    }
}
