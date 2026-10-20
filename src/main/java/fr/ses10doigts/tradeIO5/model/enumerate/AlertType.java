package fr.ses10doigts.tradeIO5.model.enumerate;

/** Types d'alertes utilisateur (affichage dans l'app) ; chacun peut être coupé par l'utilisateur. */
public enum AlertType {
    /** Action du jour bloquée (cash insuffisant, bande atteinte). */
    BUY_BLOCKED,
    /** Soldes ou données illisibles : aucune action. */
    DATA_UNAVAILABLE,
    /** Actif non tradable sur l'exchange sans passer par une monnaie fiat. */
    NOT_TRADABLE,
    /** Clé rejetée ou lecture de solde impossible. */
    CREDENTIAL_PROBLEM,
    /** Dernier plan d'ordres bloqué, partiel, en échec ou expiré. */
    PLAN_PROBLEM,
    /** Stock intermédiaire en transit (jambe 2 en attente). */
    TRANSIT_STOCK
}
