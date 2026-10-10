package fr.ses10doigts.tradeIO5.model.entity.execution;

/**
 * Statut d'une étape. {@code PLANNED} = jamais envoyée (y compris quand l'exécuteur l'a écartée : dérive de coût, étape
 * précédente en échec). {@code SUBMITTED} est posé AVANT l'envoi (écriture préalable) ; {@code UNKNOWN} = issue inconnue
 * (timeout, réponse illisible), à réconcilier par {@code clOrdId}, jamais renvoyée à l'aveugle.
 */
public enum StepStatus {
    PLANNED,
    SUBMITTED,
    FILLED,
    PARTIAL,
    REJECTED,
    CANCELED,
    UNKNOWN;

    /** Étape dont l'issue n'est pas définitive côté exchange. */
    public boolean inFlight() {
        return this == SUBMITTED || this == UNKNOWN;
    }

    /** Étape ayant effectivement acheté / vendu quelque chose. */
    public boolean executed() {
        return this == FILLED || this == PARTIAL;
    }
}
