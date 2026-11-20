package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.List;

/**
 * Plateau d'une courbe valeur → score : jamais un pic isolé. Le plateau est la plage CONTIGUË de valeurs autour du meilleur dont le score
 * est à au moins {@code ratio} du meilleur (seuil = meilleur − (1 − ratio) × |meilleur|, valable aussi quand le meilleur est négatif) ;
 * la valeur retenue est la moyenne des valeurs de cette plage.
 */
public final class TunePlateau {

    /** {@code value} = moyenne du plateau ; {@code from} / {@code to} = indices inclus dans la liste fournie ; {@code bestIndex} = le pic. */
    public record Result(double value, int from, int to, int bestIndex) {
        public int size() {
            return to - from + 1;
        }
    }

    private TunePlateau() {
    }

    /** @param values croissantes ; @param scores même taille, les {@code NaN} (valeur non évaluable) coupent le plateau ; {@code null} si rien d'évaluable */
    public static Result pick(List<Double> values, List<Double> scores, double ratio) {
        int best = -1;
        for (int i = 0; i < scores.size(); i++) {
            double s = scores.get(i);
            if (!Double.isNaN(s) && (best < 0 || s > scores.get(best))) {
                best = i;
            }
        }
        if (best < 0) {
            return null;
        }
        double top = scores.get(best);
        double threshold = top - (1.0 - ratio) * Math.abs(top);
        int lo = best;
        int hi = best;
        while (lo > 0 && ok(scores.get(lo - 1), threshold)) {
            lo--;
        }
        while (hi < scores.size() - 1 && ok(scores.get(hi + 1), threshold)) {
            hi++;
        }
        double sum = 0;
        for (int i = lo; i <= hi; i++) {
            sum += values.get(i);
        }
        return new Result(sum / (hi - lo + 1), lo, hi, best);
    }

    private static boolean ok(double score, double threshold) {
        return !Double.isNaN(score) && score >= threshold;
    }
}
