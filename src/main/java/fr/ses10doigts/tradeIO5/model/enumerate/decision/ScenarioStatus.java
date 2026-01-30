package fr.ses10doigts.tradeIO5.model.enumerate.decision;

public enum ScenarioStatus {
    INITIAL,
    EMERGING,     // hypothèse faible
    CONFIRMING,   // se renforce
    VALIDATED,    // prête à agir
    INVALIDATED,  // cassée
    EXPIRED       // plus pertinente
    ;

    public boolean isActive() {
        return this != INVALIDATED && this != EXPIRED;
    }
}
