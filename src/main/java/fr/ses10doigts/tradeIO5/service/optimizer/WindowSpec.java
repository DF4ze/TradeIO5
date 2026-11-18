package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.List;

/**
 * Fenêtre d'évaluation ancrée à la date de fin : {@code months} nul = historique complet. {@code from} (facultatif) fixe le premier
 * jour au lieu de {@code fin − months} : fenêtre de test strictement postérieure à l'entraînement d'un pli.
 */
public record WindowSpec(String name, Integer months, int weight, LocalDate from) {

    public WindowSpec {
        if (weight < 1) {
            throw new IllegalArgumentException("Poids de fenêtre >= 1");
        }
    }

    /** Fenêtre ancrée à la date de fin (premier jour = fin − {@code months}, ou premier jour éligible si {@code months} est nul). */
    public WindowSpec(String name, Integer months, int weight) {
        this(name, months, weight, null);
    }

    /** 12 mois (poids 3), 24 mois (poids 2), historique complet (poids 1). */
    public static List<WindowSpec> defaults() {
        return List.of(new WindowSpec("12m", OptimizerDefaults.WINDOW_SHORT_MONTHS, OptimizerDefaults.WINDOW_SHORT_WEIGHT),
                new WindowSpec("24m", OptimizerDefaults.WINDOW_MEDIUM_MONTHS, OptimizerDefaults.WINDOW_MEDIUM_WEIGHT),
                new WindowSpec("full", null, OptimizerDefaults.WINDOW_FULL_WEIGHT));
    }
}
