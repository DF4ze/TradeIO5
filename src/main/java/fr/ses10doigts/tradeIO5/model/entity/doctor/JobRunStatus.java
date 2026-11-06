package fr.ses10doigts.tradeIO5.model.entity.doctor;

/** Issue d'une exécution de job planifié. */
public enum JobRunStatus {
    /** Terminé sans erreur. */
    OK,
    /** Terminé, mais au moins un élément traité en erreur (le job a isolé l'échec). */
    PARTIAL,
    /** Exception remontée par le job. */
    FAILED
}
