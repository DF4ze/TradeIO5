package fr.ses10doigts.tradeIO5.model.enumerate.decision;

public enum ScenarioType {
    TREND_UP,      // tendance haussière
    TREND_DOWN,    // tendance baissière
    RANGE,         // marché en range / consolidation
    VOLATILE,      // forte volatilité sans direction claire
    BREAKOUT_UP,   // cassure haussière d’une zone clé
    BREAKOUT_DOWN, // cassure baissière d’une zone clé
    CRASH,         // chute brutale
    SURGE          // hausse brutale
}