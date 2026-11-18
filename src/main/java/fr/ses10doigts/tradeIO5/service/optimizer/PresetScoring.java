package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Évaluation L1 d'un jeu de presets : {@link ReplayScorer} sur des entrées dont les jeux Bear / Bull sont remplacés (Trend, base,
 * ATH, politique inchangés), plus le veto d'exposition : exposition moyenne d'une fenêtre notée supérieure à la borne haute de la
 * bande du profil ⇒ jeu vetoé ({@link OptimizerDefaults#VETO_EXPOSURE}). Fonction pure.
 */
public final class PresetScoring {

    private PresetScoring() {
    }

    /** Mêmes entrées avec ces jeux de presets sur chaque lien. */
    public static ScorerInputs withSets(ScorerInputs in, RainbowAtrParamSet[] sets) {
        return in.withAssets(in.assets().stream().map(a -> a.withTemplate(a.template().withSets(sets))).toList());
    }

    /** @param bandMaxPct borne haute de la bande d'exposition du profil, en % */
    public static ScoreReport score(ScorerInputs in, RainbowAtrParamSet[] sets, ResolvedCandidate resolved, CostMode mode,
                                    boolean shortWindowOnly, double bandMaxPct) {
        ScoreReport report = ReplayScorer.score(withSets(in, sets), resolved, mode, shortWindowOnly);
        if (!exceedsBand(report, bandMaxPct)) {
            return report;
        }
        List<String> vetoes = new ArrayList<>(report.vetoes());
        vetoes.add(OptimizerDefaults.VETO_EXPOSURE);
        return new ScoreReport(OptimizerDefaults.VETO_SCORE, report.rawScore(), true, List.copyOf(vetoes), report.medianGainPct(),
                report.dispersionPct(), report.penalty(), report.windows(), report.excluded(), report.pureTiming(), report.costMode(),
                report.fingerprint());
    }

    /** Une fenêtre notée dont l'exposition moyenne dépasse la bande (l'historique complet n'est que rapporté). */
    static boolean exceedsBand(ScoreReport report, double bandMaxPct) {
        return report.windows().stream().filter(ScoreReport.WindowReport::scored)
                .anyMatch(w -> w.avgExposurePct() > bandMaxPct + OptimizerDefaults.EPSILON);
    }
}
