package fr.ses10doigts.tradeIO5.service.optimizer;

import java.util.List;

/** Fonction à maximiser, pure et thread-safe : le moteur ne connaît ni les noms de paramètres ni le simulateur. */
public interface SearchObjective {

    Evaluation evaluate(Candidate candidate, Fidelity fidelity, Scenario scenario);

    /** Mondes bousculés dans lesquels le candidat retenu est réévalué ; vide par défaut. */
    default List<Scenario> bumpScenarios() {
        return List.of();
    }
}
