package fr.ses10doigts.tradeIO5.model.entity.doctor;

/** Cycle de vie d'une alarme : ouverte, acquittée (vue, en cours de traitement ou mise en sommeil), résolue (contrôle revenu OK). */
public enum AlarmState {
    ACTIVE, ACKED, RESOLVED
}
