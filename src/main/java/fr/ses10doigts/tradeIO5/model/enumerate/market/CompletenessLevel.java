package fr.ses10doigts.tradeIO5.model.enumerate.market;

public enum CompletenessLevel {
    COMPLETE,        // structure OK + période close
    PARTIAL_LAST,    // structure OK mais dernière période ouverte
    INCOMPLETE       // trou / manque de données
}
