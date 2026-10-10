package fr.ses10doigts.tradeIO5.model.entity.execution;

/**
 * Statut d'un plan d'ordres. Planification : {@code PLANNED}, {@code BLOCKED} (actions voulues sans étape), {@code DISABLED},
 * {@code SUPERSEDED} (remplacé par une révision plus récente, ligne d'audit conservée). {@code EXPIRED} est dérivé à la
 * lecture (plan {@code PLANNED} dont {@code expiresAt} est passé) et jamais persisté. Exécution : {@code EXECUTING} puis
 * {@code EXECUTED} (toutes les étapes remplies), {@code PARTIAL} (une partie seulement), {@code FAILED} (rien d'exécuté) ;
 * {@code BLOCKED} sert aussi aux blocages définitifs de l'exécuteur (plafond, non tradable) ; {@code CANCELLED} réservé à
 * l'annulation explicite.
 */
public enum PlanStatus {
    PLANNED,
    BLOCKED,
    EXPIRED,
    DISABLED,
    SUPERSEDED,
    EXECUTING,
    EXECUTED,
    PARTIAL,
    FAILED,
    CANCELLED
}
