package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.List;

/**
 * Résultat d'une évaluation : score (jeu vetoé = {@link OptimizerDefaults#VETO_SCORE}), vetos rencontrés et détail libre
 * (rapport de score) conservé tel quel dans le rapport final.
 */
public record Evaluation(double score, boolean vetoed, List<String> vetoes, Object detail) {

    public Evaluation {
        vetoes = vetoes == null ? List.of() : List.copyOf(vetoes);
    }

    public static Evaluation ok(double score, Object detail) {
        return new Evaluation(score, false, List.of(), detail);
    }
}
