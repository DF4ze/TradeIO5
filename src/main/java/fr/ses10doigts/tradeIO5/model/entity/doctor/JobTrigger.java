package fr.ses10doigts.tradeIO5.model.entity.doctor;

/** Origine d'une exécution de job. */
public enum JobTrigger {
    /** Déclenchée par le scheduler. */
    CRON,
    /** Déclenchée à la main depuis l'Administration. */
    MANUAL
}
