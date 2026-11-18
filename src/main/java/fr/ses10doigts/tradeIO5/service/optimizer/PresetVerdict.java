package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.PresetOptimizationReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.WeightedStats.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Règle de décision par actif (fonction pure). {@code ADOPTER} (le challenger est retenu comme candidat ; rien n'est appliqué) seulement
 * si : il bat le preset en place hors échantillon sur la MAJORITÉ des plis exploitables, aucun veto bloquant en test, la médiane de l'écart
 * hors échantillon est strictement positive, et moins de {@link OptimizerDefaults#PRESET_MAX_TRAIN_BLOCKED_SHARE} des plis ont un entraînement
 * vetoé. Sinon {@code GARDER_PRESET} : un résultat nul est un résultat valide.
 */
public final class PresetVerdict {

    private PresetVerdict() {
    }

    /**
     * @param evaluated    test rejoué pour le preset et pour le challenger (un pli à entraînement vetoé compte pour un écart nul)
     * @param won          challenger meilleur que le preset, sans veto bloquant
     * @param trainBlocked entraînement vetoé (tous les candidats, ou le preset) : {@code GARDER_PRESET}
     */
    public record FoldScore(boolean evaluated, boolean won, boolean testVeto, boolean trainBlocked, Double deltaPts) {
    }

    /** @param folds plis exploitables seulement (les plis à historique insuffisant sont rapportés à part) */
    public static Verdict decide(List<FoldScore> folds, int usableFolds) {
        int majority = usableFolds / 2 + 1;
        int wins = (int) folds.stream().filter(FoldScore::won).count();
        List<FoldScore> evaluated = folds.stream().filter(FoldScore::evaluated).toList();
        int blocked = (int) folds.stream().filter(FoldScore::trainBlocked).count();
        double share = usableFolds == 0 ? 0.0 : (double) blocked / usableFolds;
        boolean veto = evaluated.stream().anyMatch(FoldScore::testVeto);
        List<Point> deltas = evaluated.stream().filter(f -> f.deltaPts() != null).map(f -> new Point(f.deltaPts(), 1)).toList();
        Double median = deltas.isEmpty() ? null : WeightedStats.median(deltas);
        List<String> reasons = new ArrayList<>();
        if (evaluated.isEmpty()) {
            reasons.add("aucun pli évalué");
        }
        if (wins < majority) {
            reasons.add(String.format(Locale.ROOT, "minorité de plis gagnés (%d/%d, majorité requise %d)", wins, usableFolds, majority));
        }
        if (veto) {
            reasons.add("veto en test");
        }
        if (median != null && median <= 0) {
            reasons.add(String.format(Locale.ROOT, "médiane de l'écart hors échantillon non positive (%+.2f pt du capital)", median));
        }
        if (share >= OptimizerDefaults.PRESET_MAX_TRAIN_BLOCKED_SHARE) {
            reasons.add(String.format(Locale.ROOT, "entraînement vetoé sur %d/%d plis (%.0f %% >= %.0f %%)", blocked, usableFolds, 100 * share,
                    100 * OptimizerDefaults.PRESET_MAX_TRAIN_BLOCKED_SHARE));
        }
        boolean adopt = reasons.isEmpty() && median != null;
        if (adopt) {
            reasons.add(String.format(Locale.ROOT, "bat le preset sur %d/%d plis, aucun veto en test, médiane de l'écart %+.2f pt du capital",
                    wins, usableFolds, median));
        }
        return new Verdict(adopt ? OptimizerDefaults.VERDICT_ADOPT : OptimizerDefaults.VERDICT_KEEP_PRESET, List.copyOf(reasons), wins,
                majority, evaluated.size(), usableFolds, blocked, share, median, veto);
    }
}
