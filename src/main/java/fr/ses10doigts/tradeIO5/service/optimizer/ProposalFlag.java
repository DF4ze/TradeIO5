package fr.ses10doigts.tradeIO5.service.optimizer;

/** Drapeaux d'une proposition de nouvel actif : informatifs, jamais bloquants. */
public enum ProposalFlag {
    /** Historique inférieur à {@link NewAssetConfig#provisionalMonths()} mois. */
    PROVISOIRE,
    /** Aucune fenêtre de 24 mois rejouable : le pire cas n'est calé que sur l'historique complet. */
    MARCHE_TROP_COURT,
    /** Pas assez de bougies (ou source injoignable) : ni preset rejoué ni taux ; l'actif reste sur le repli BTC « non calibré ». */
    HISTORIQUE_INSUFFISANT,
    /** Actif non tradable chez le provider du lien (ou sans chemin d'achat) : proposition quand même, avec avertissement. */
    NON_TRADABLE,
    /** Coût estimé du calage au-dessus du budget : calage non lancé (preset seul). */
    CALAGE_COUT_ELEVE,
    /** Calcul interrompu (délai maximal atteint ou erreur) : repli sur le preset BTC non calibré, aucune exception remontée. */
    CALAGE_INTERROMPU
}
