package fr.ses10doigts.tradeIO5.model.entity.admin;

/** Issue d'une action admin : exécutée, refusée (garde-fou, entrée invalide, rien d'envoyé) ou en erreur inattendue. */
public enum AdminActionOutcome {
    OK,
    REFUSED,
    ERROR
}
