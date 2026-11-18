package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.PolicyCalibrationReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.WeightedStats.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Règle de décision par profil (fonction pure). {@code ADOPTER} seulement si : la politique retenue bat le point de départ hors
 * échantillon sur la MAJORITÉ des plis ({@code plis / 2 + 1}), aucun veto bloquant en test, et la médiane des gains hors échantillon vs DCA
 * fixe de référence est {@code >= 0}. Sinon {@code GARDER_DEPART} : un résultat nul est un résultat valide.
 */
public final class PolicyVerdict {

    private PolicyVerdict() {
    }

    /** Ce que la règle lit d'un pli : évalué, gagné (bat le départ, sans veto), veto de test du candidat retenu, gain hors échantillon (%). */
    public record FoldScore(boolean evaluated, boolean won, boolean testVeto, Double testGainPct) {
    }

    /** @param totalFolds plis demandés (un pli non évalué compte parmi eux et n'est jamais gagné) */
    public static Verdict decide(List<FoldScore> folds, int totalFolds) {
        int majority = totalFolds / 2 + 1;
        int wins = (int) folds.stream().filter(FoldScore::won).count();
        List<FoldScore> evaluated = folds.stream().filter(FoldScore::evaluated).toList();
        boolean veto = evaluated.stream().anyMatch(FoldScore::testVeto);
        List<Point> gains = evaluated.stream().filter(f -> f.testGainPct() != null).map(f -> new Point(f.testGainPct(), 1)).toList();
        Double median = gains.isEmpty() ? null : WeightedStats.median(gains);
        List<String> reasons = new ArrayList<>();
        if (evaluated.isEmpty()) {
            reasons.add("aucun pli évalué");
        }
        if (wins < majority) {
            reasons.add(String.format(Locale.ROOT, "minorité de plis gagnés (%d/%d, majorité requise %d)", wins, totalFolds, majority));
        }
        if (veto) {
            reasons.add("veto en test");
        }
        if (median != null && median < 0) {
            reasons.add(String.format(Locale.ROOT, "médiane hors échantillon négative (%+.2f %% du capital)", median));
        }
        boolean adopt = reasons.isEmpty() && median != null;
        if (adopt) {
            reasons.add(String.format(Locale.ROOT, "bat le départ sur %d/%d plis, aucun veto en test, médiane hors échantillon %+.2f %% du capital",
                    wins, totalFolds, median));
        }
        return new Verdict(adopt ? OptimizerDefaults.VERDICT_ADOPT : OptimizerDefaults.VERDICT_KEEP_START, List.copyOf(reasons), wins, majority,
                evaluated.size(), median, veto);
    }
}
