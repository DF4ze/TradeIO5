package fr.ses10doigts.tradeIO5.model.entity.admin;

/** Actions mutantes de l'administration d'exécution tracées au journal {@link AdminActionLog}. */
public enum AdminAction {
    KILL_SWITCH,
    ORDER_VALIDATE,
    ARM_BINDING,
    PLAN,
    RUN,
    RECONCILE,
    CREDENTIAL_STORED,
    CREDENTIAL_ENABLED,
    AUTO_REENGAGE
}
