package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/** Raison pour laquelle l'action live d'une passe n'a pas pu être prise telle que voulue par le moteur. */
public enum LiveBlockReason {
    NONE,
    /** Achat voulu supérieur au cash restant du pool (tout-ou-rien). */
    INSUFFICIENT_CASH,
    /** Lecture du portefeuille réel indisponible ou périmée. */
    UNAVAILABLE,
    /** Couple (actif, exchange) non tradable sans passer par une monnaie fiat : exécution bloquée. */
    NOT_TRADABLE_WITHOUT_FIAT
}
