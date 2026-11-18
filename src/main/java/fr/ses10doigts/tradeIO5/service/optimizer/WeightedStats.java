package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Médiane pondérée et dispersion du score (fonctions pures). */
public final class WeightedStats {

    private WeightedStats() {
    }

    /** Valeur pondérée (valeur, poids). */
    public record Point(double value, int weight) {
    }

    /** Médiane pondérée : plus petite valeur dont le poids cumulé atteint la moitié ; à égalité exacte, moyenne avec la suivante. */
    public static double median(List<Point> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Médiane d'un ensemble vide");
        }
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(Point::value));
        double total = sorted.stream().mapToInt(Point::weight).sum();
        double cumulative = 0;
        for (int i = 0; i < sorted.size(); i++) {
            cumulative += sorted.get(i).weight();
            if (cumulative * 2 >= total) {
                boolean exactHalf = cumulative * 2 == total && i + 1 < sorted.size();
                return exactHalf ? (sorted.get(i).value() + sorted.get(i + 1).value()) / 2.0 : sorted.get(i).value();
            }
        }
        return sorted.getLast().value();
    }

    /** Écart absolu moyen pondéré autour de {@code center}. */
    public static double meanAbsoluteDeviation(List<Point> points, double center) {
        double total = points.stream().mapToInt(Point::weight).sum();
        return points.stream().mapToDouble(p -> p.weight() * Math.abs(p.value() - center)).sum() / total;
    }
}
