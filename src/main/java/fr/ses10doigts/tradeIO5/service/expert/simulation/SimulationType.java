package fr.ses10doigts.tradeIO5.service.expert.simulation;

/** Ce que rejoue une simulation de l'Espace Expert. */
public enum SimulationType {
    /** Un preset Trend seul : phases UP / DOWN, bascules, métriques. */
    TREND,
    /** Un preset Rainbow sur un seul régime (UP ou DOWN) d'un Trend de référence ; hors régime, la barre est inactive. */
    RAINBOW,
    /** Trend + Rainbow UP + Rainbow DOWN : chaque barre active joue le jeu de son régime. */
    AFFECTATION
}
