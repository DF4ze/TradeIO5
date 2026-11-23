package fr.ses10doigts.tradeIO5.service.execution.run;

/** Publié par {@link OrderExecutor} quand un plan reste {@code EXECUTING} (step en vol) : déclenche {@link ExecutionReconcileWatcher}. */
public record PlanInFlightEvent(Long planId) {
}
