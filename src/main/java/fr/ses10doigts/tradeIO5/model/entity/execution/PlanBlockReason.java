package fr.ses10doigts.tradeIO5.model.entity.execution;

/** Raison du blocage d'un actif dans un plan d'ordres. */
public enum PlanBlockReason {
    /** Aucun chemin chiffrable (marchés indisponibles ou carnet trop peu profond). */
    NO_PATH,
    /** Une jambe est sous le minimum de l'exchange. */
    BELOW_MIN,
    /** Le solde du groupe USD (USDC) ne couvre pas le montant. */
    INSUFFICIENT_FUNDS_AFTER_FEES,
    /** Aucune route (paire stable, ou intermédiaire choisi coté en stable) pour cet actif sur l'exchange : il faudrait passer par une monnaie fiat. */
    NOT_TRADABLE_WITHOUT_FIAT,
    /** Lecture du portefeuille ou des marchés indisponible, périmée ou absente. */
    READING_UNAVAILABLE
}
