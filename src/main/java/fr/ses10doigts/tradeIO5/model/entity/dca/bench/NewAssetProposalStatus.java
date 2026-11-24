package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/** Statut d'une proposition d'accueil d'un nouvel actif : rien n'est appliqué tant qu'un admin n'a pas validé (D1b). */
public enum NewAssetProposalStatus {
    /** Calculée, en attente d'un admin ; l'actif tourne déjà sur le repli BTC « non calibré ». */
    PROPOSE,
    /** Validée par un admin (lot 2 : applique les taux de l'actif et l'affectation du preset BTC). */
    VALIDE,
    /** Refusée par un admin : l'actif reste sur le repli BTC « non calibré ». */
    REFUSE
}
