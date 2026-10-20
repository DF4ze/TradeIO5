package fr.ses10doigts.tradeIO5.model.entity.admin;

/** Actions mutantes de l'administration d'exécution tracées au journal {@link AdminActionLog}. */
public enum AdminAction {
    KILL_SWITCH,
    ORDER_VALIDATE,
    /** Envoi d'un ordre RÉEL depuis le testeur d'ordre. */
    ORDER_SEND,
    /** Relecture (lecture seule) d'un ordre du testeur. */
    ORDER_READ,
    ARM_BINDING,
    PLAN,
    RUN,
    RECONCILE,
    CREDENTIAL_STORED,
    CREDENTIAL_ENABLED,
    AUTO_REENGAGE,
    /** Modification des paramètres admin de la modulation Rainbow. */
    MODULATION
}
