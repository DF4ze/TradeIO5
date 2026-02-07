package fr.ses10doigts.tradeIO5.model.enumerate.decision;

public enum ScenarioEventType {
    SCENARIO_CREATED,
    STATE_MUTATED,          // confiance / signal
    STATUS_CHANGED,         // EMERGING → CONFIRMING, etc.
    SCENARIO_ENRICHED,
    SCENARIO_EXPIRED,
    SCENARIO_INVALIDATED,
    ACTION_PROPOSED
}