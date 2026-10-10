package fr.ses10doigts.tradeIO5.model.entity.execution;

/** Type d'un événement du journal d'audit d'exécution (append-only). */
public enum OrderEventType {
    /** Exécution démarrée (plan pris en charge). */
    PLAN_STARTED,
    /** Exécution refusée ou limitée : mode, kill switch, binding, garde, credential, double validation, plafond, expiration. */
    BLOCKED,
    /** Re-devis juste avant une étape (coût projeté, décision). */
    STEP_REQUOTED,
    /** Étape non envoyée (dérive de coût, étape précédente en échec, taille sous le minimum). */
    STEP_SKIPPED,
    /** Étape écrite {@code SUBMITTED} avant l'envoi. */
    STEP_SUBMITTING,
    /** Accusé de l'exchange. */
    STEP_ACK,
    /** Issue définitive d'une étape (statut, quantités, frais, slippage réel). */
    STEP_RESULT,
    /** Issue inconnue (timeout, réponse illisible) : réconciliation requise. */
    STEP_UNKNOWN,
    /** Étape relue par {@code clOrdId} (réconciliation ou doublon). */
    STEP_RECONCILED,
    /** Reliquat d'un achat partiel, reporté au cycle suivant. */
    PARTIAL_REMAINDER,
    /** Alerte (échec partiel, issue inconnue). */
    ALERT,
    /** Statut final du plan. */
    PLAN_RESULT
}
