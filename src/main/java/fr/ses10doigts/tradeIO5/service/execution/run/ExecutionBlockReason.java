package fr.ses10doigts.tradeIO5.service.execution.run;

/**
 * Motif pour lequel l'exécuteur n'envoie pas (ou plus) d'ordre. {@code permanent} : le plan ne pourra jamais passer tel
 * quel (plafond, non tradable) => statut {@code BLOCKED} ; sinon le plan reste {@code PLANNED} (ré-exécutable tant qu'il n'est pas expiré).
 */
public enum ExecutionBlockReason {
    MODE_NOT_LIVE(false),
    PORT_UNAVAILABLE(false),
    KILL_SWITCH(false),
    PLAN_EXPIRED(false),
    PLAN_NOT_EXECUTABLE(false),
    NO_BINDING(false),
    BINDING_DISABLED(false),
    NOT_TRADABLE(true),
    CREDENTIAL_INVALID(false),
    FIRST_LIVE_NOT_VALIDATED(false),
    NO_BASE_AMOUNT(false),
    CAP_ORDER(true),
    CAP_DAY(true),
    CAP_ABSOLUTE_ORDER(true),
    CAP_ABSOLUTE_DAY(true);

    private final boolean permanent;

    ExecutionBlockReason(boolean permanent) {
        this.permanent = permanent;
    }

    public boolean permanent() {
        return permanent;
    }
}
