package fr.ses10doigts.tradeIO5.model.enumerate.tree;

/**
 * Action globale proposée par une Decision.
 */
public enum MarketIntentAction {

    BUY,
    SELL,
    HOLD,
    ADJUST,     // ajustement (grid, stop, pondération…)
    SUSPEND     // suspension temporaire des actions
}