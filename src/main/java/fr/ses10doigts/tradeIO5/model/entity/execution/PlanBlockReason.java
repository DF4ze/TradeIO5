package fr.ses10doigts.tradeIO5.model.entity.execution;

/** Raison du blocage d'un actif dans un plan d'ordres. */
public enum PlanBlockReason {
    /** Aucun chemin chiffrable (marchés indisponibles ou carnet trop peu profond). */
    NO_PATH,
    /** Une jambe est sous le minimum de l'exchange. */
    BELOW_MIN,
    /** Fee Test rouge : coût du chemin au-dessus du seuil rouge. */
    /** Les soldes ne couvrent pas le montant une fois les frais du pont ajoutés. */
    INSUFFICIENT_FUNDS_AFTER_FEES,
    /** Aucune paire stable pour cet actif sur l'exchange : il faudrait passer par une monnaie fiat. */
    NOT_TRADABLE_WITHOUT_FIAT,
    /** Lecture du portefeuille ou des marchés indisponible, périmée ou absente. */
    READING_UNAVAILABLE
}
