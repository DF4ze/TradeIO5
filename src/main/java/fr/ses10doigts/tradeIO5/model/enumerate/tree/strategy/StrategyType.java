package fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy;

public enum StrategyType {
    ENTRY,
    EXIT,

    /**
     * Volontairement non implémenté.
     * <p>
     * Une vraie taille de position / distance de stop a besoin du wallet et du profil de
     * risque de l'utilisateur ({@code WalletSnapshot}/{@code UserProfile}), qui n'existent
     * que dans {@code OpinionContext} — {@link fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy#evaluate}
     * ne reçoit qu'un {@code MarketContext} et ne peut donc pas porter cette logique
     * correctement. Le sizing/stop est un sujet de la couche Decision/exécution, pas de
     * la couche Strategy : à traiter séparément, pas en ajoutant une Strategy RISK ici.
     */
    RISK
}