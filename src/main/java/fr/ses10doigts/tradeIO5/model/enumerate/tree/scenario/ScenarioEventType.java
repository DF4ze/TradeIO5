package fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario;

public enum ScenarioEventType {
    SCENARIO_CREATED,
    STATE_MUTATED,          // confiance / weightedSignal
    STATUS_CHANGED,         // EMERGING → CONFIRMING, etc.
    SCENARIO_ENRICHED,
    SCENARIO_EXPIRED,
    SCENARIO_INVALIDATED,
    ACTION_PROPOSED
}