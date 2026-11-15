package fr.ses10doigts.tradeIO5.service.expert.simulation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Mesure pure du drawdown d'une série de valeurs journalières (aucun I/O, aucun Spring) : une seule fonction pour le wallet
 * stratégie et pour la référence DCA fixe. Drawdown (en points de %, ≤ 0) = {@code 100 × (valeur / plus haut précédent − 1)} ;
 * le plus haut initial est la première valeur. Signalement seul : rien ici n'influence un ordre.
 */
public final class DrawdownMeter {

    private DrawdownMeter() {
    }

    /**
     * Mesure d'une série. {@code drawdownPct} : une entrée par jour (≤ 0). {@code maxPct} : valeur la plus basse (0 si jamais sous
     * son plus haut) ; {@code peakDay} / {@code troughDay} / {@code recoveryDay} : plus haut précédant le creux, creux, premier jour
     * de retour au plus haut après le creux (null si jamais revenu ou si aucun drawdown). {@code longestUnderwaterDays} : plus long
     * enchaînement de jours consécutifs sous le plus haut. {@code lastPct} : drawdown du dernier jour.
     */
    public record Measure(List<Double> drawdownPct, double maxPct, LocalDate peakDay, LocalDate troughDay, LocalDate recoveryDay,
                          int longestUnderwaterDays, double lastPct) {
    }

    /**
     * Verdict d'un plafond sur une mesure. Le plafond est un % négatif ; {@code respected} = le drawdown max n'est jamais sous le
     * plafond (égalité exacte = respecté). {@code firstBreachDay} : 1er jour strictement sous le plafond (null si respecté) ;
     * {@code maxOverrunPts} : dépassement maximal en points (≥ 0, 0 si respecté).
     */
    public record Verdict(double capPct, boolean respected, LocalDate firstBreachDay, double maxOverrunPts) {
    }

    /** {@link IllegalArgumentException} si la série est vide, de tailles incohérentes ou contient une valeur nulle, négative ou non finie. */
    public static Measure measure(List<LocalDate> days, List<? extends Number> values) {
        if (days == null || values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Série vide : drawdown non mesurable");
        }
        if (days.size() != values.size()) {
            throw new IllegalArgumentException("Dates et valeurs de longueurs différentes : " + days.size() + " / " + values.size());
        }
        List<Double> dd = new ArrayList<>(values.size());
        double peak = 0;
        int peakIdx = 0;
        double maxPct = 0;
        int maxPeakIdx = 0;
        int troughIdx = -1;
        int underwater = 0;
        int longest = 0;
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i).doubleValue();
            if (!Double.isFinite(v) || v <= 0) {
                throw new IllegalArgumentException("Valeur de wallet nulle, négative ou non finie au " + days.get(i) + " : " + v);
            }
            if (i == 0 || v > peak) {
                peak = v;
                peakIdx = i;
            }
            double pct = v < peak ? 100.0 * (v / peak - 1.0) : 0.0;
            dd.add(pct);
            underwater = pct < 0 ? underwater + 1 : 0;
            longest = Math.max(longest, underwater);
            if (pct < maxPct) {
                maxPct = pct;
                maxPeakIdx = peakIdx;
                troughIdx = i;
            }
        }
        LocalDate recovery = null;
        if (troughIdx >= 0) {
            double maxPeak = values.get(maxPeakIdx).doubleValue();
            for (int i = troughIdx + 1; i < values.size(); i++) {
                if (values.get(i).doubleValue() >= maxPeak) {
                    recovery = days.get(i);
                    break;
                }
            }
        }
        return new Measure(dd, maxPct, troughIdx < 0 ? null : days.get(maxPeakIdx), troughIdx < 0 ? null : days.get(troughIdx), recovery,
                longest, dd.getLast());
    }

    /** Verdict de {@code capPct} (% négatif, ex. -25) sur la série mesurée ({@code days} = celles de {@link #measure}). */
    public static Verdict verdict(List<LocalDate> days, Measure measure, double capPct) {
        if (!(capPct <= 0) || capPct < -100) {
            throw new IllegalArgumentException("Plafond de drawdown hors [-100 ; 0] : " + capPct);
        }
        LocalDate first = null;
        for (int i = 0; i < measure.drawdownPct().size(); i++) {
            if (measure.drawdownPct().get(i) < capPct) {
                first = days.get(i);
                break;
            }
        }
        boolean respected = first == null;
        return new Verdict(capPct, respected, first, respected ? 0.0 : capPct - measure.maxPct());
    }
}
