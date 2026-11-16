package fr.ses10doigts.tradeIO5.service.expert.simulation.policy;

import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.time.LocalDate;
import java.util.Map;

/**
 * État du wallet au début du jour {@code day}, aucune donnée postérieure au jour courant : positions et clôtures du jour sont
 * connues, le plus haut vient des fins de journée précédentes uniquement.
 *
 * @param assetsValue        valeur des actifs aux clôtures du jour (exposition inconnue : valeur de la veille)
 * @param totalValue         cash + {@code assetsValue}
 * @param peakValue          max des valeurs totales de fin de journée passées ; initial = capital de départ
 * @param drawdownPct        {@code 100 × (totalValue / peakValue − 1)}, ≤ 0
 * @param exposurePct        part des actifs dans la valeur du wallet ; {@code null} si inconnue (même règle que le live)
 * @param regimeByAsset      régime Trend (UP / DOWN / RANGE) des liens actifs du jour
 * @param daysInRegimeByAsset jours consécutifs (jour courant inclus) dans le régime courant de chaque actif actif
 */
public record DayState(LocalDate day, double cash, double assetsValue, double totalValue, double peakValue, double drawdownPct,
                       Double exposurePct, Map<String, String> regimeByAsset, Map<String, Integer> daysInRegimeByAsset) {

    /** Régime majoritaire parmi les liens actifs ; {@code null} si aucun lien actif ou égalité. */
    public String majorityRegime() {
        String best = null;
        long bestCount = 0;
        boolean tie = false;
        for (String regime : regimeByAsset.values().stream().distinct().toList()) {
            long count = regimeByAsset.values().stream().filter(regime::equals).count();
            if (count > bestCount) {
                best = regime;
                bestCount = count;
                tie = false;
            } else if (count == bestCount) {
                tie = true;
            }
        }
        return tie ? null : best;
    }

    /** Plus longue durée (jours) dans {@code regime} parmi les liens qui y sont ; 0 si aucun. */
    public int maxDaysInRegime(String regime) {
        return regimeByAsset.entrySet().stream().filter(e -> e.getValue().equals(regime))
                .mapToInt(e -> daysInRegimeByAsset.getOrDefault(e.getKey(), 0)).max().orElse(0);
    }

    public static final String BULL = TrendRegime.UP.name();
    public static final String BEAR = TrendRegime.DOWN.name();
}
