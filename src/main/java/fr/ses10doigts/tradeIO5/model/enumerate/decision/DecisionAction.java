package fr.ses10doigts.tradeIO5.model.enumerate.decision;

/**
 * Action globale proposée par une Decision.
 */
public enum DecisionAction {

    BUY,
    SELL,
    HOLD,
    ADJUST,     // ajustement (grid, stop, pondération…)
    SUSPEND     // suspension temporaire des actions
}