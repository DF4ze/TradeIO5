package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.List;
import java.util.Map;

/**
 * Sortie de {@link SearchEngine}. Tout est déterministe (mêmes entrées ⇒ mêmes valeurs) sauf {@link Timings}.
 *
 * @param space          espace demandé
 * @param searchedSpace  espace après gel des paramètres sans effet
 * @param trials         nombre d'évaluations réellement calculées (tous budgets et mondes confondus, mémoïsation exclue)
 * @param vetoCounts     vetos rencontrés (motif → nombre d'évaluations)
 */
public record SearchResult(ParamSpace space, ParamSpace searchedSpace, long seed, List<Sensitivity> sensitivities,
                           List<Finalist> finalists, Plateau plateau, Candidate chosen, Evaluation chosenEvaluation,
                           List<Bump> bumps, Robustness robustness, long trials, Map<String, Integer> vetoCounts,
                           Timings timings) {

    /**
     * Effet d'un paramètre sur le score (un à la fois autour du point de départ), mesuré entre jeux NON vetoés ; {@code vetoedPoints} =
     * points vetoés de la grille (un paramètre qui déclenche des vetos n'est jamais gelé).
     */
    public record Sensitivity(String name, double effect, double minScore, double maxScore, double bestValue, int vetoedPoints,
                              boolean frozen) {
    }

    /** Candidat de plus haut budget : score propre et score de plateau. */
    public record Finalist(Candidate candidate, double score, double plateauScore, int plateauSize, boolean vetoed) {
    }

    /**
     * Plateau retenu : {@code plateauScore} = médiane des scores du centre et de ses voisins à ±1 cran ; {@code size} = voisins non
     * vetoés à au plus {@code band} sous le centre.
     */
    public record Plateau(Candidate center, double centerScore, double plateauScore, int size, int neighbors, double band,
                          List<NeighborScore> neighborScores) {
    }

    public record NeighborScore(Candidate candidate, double score, boolean vetoed) {
    }

    /** Réévaluation du candidat retenu dans un monde bousculé ou sur un voisin. */
    public record Bump(String label, double score, boolean vetoed, double relativeGap, boolean tolerated) {
    }

    /** Verdict de robustesse : tous les écarts relatifs sont sous la tolérance et aucun jeu bousculé n'est vetoé. */
    public record Robustness(double tolerance, double floor, boolean robust, double worstRelativeGap) {
    }

    /** Hors reproductibilité : durées mesurées. */
    public record Timings(long totalMs, long evaluationMs, double msPerRun) {
    }
}
