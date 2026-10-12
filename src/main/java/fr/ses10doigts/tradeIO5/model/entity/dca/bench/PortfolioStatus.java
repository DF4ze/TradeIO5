package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/** Fiabilité d'une lecture de portefeuille réel : seule {@link #OK} autorise une action live. */
public enum PortfolioStatus {
    /** Lecture exploitable. */
    OK,
    /** Panne, clé rejetée ou lecture vide suspecte : aucune action live (jamais un 0 silencieux). */
    UNAVAILABLE,
    /** Couple (actif, exchange) sans chemin spot sans fiat : aucune action live, avertissement à l'utilisateur. */
    NOT_TRADABLE
}
