package fr.ses10doigts.tradeIO5.model.entity.execution;

/** Sort d'un actif dans un plan : étapes calculées, bloqué (raison), non activé, ou rien à faire. */
public enum AssetPlanOutcome {
    OK,
    BLOCKED,
    DISABLED,
    NO_ACTION
}
