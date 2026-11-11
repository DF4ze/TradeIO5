package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import lombok.Getter;

/** Règle métier des Affectations violée : nom déjà pris, instance Système en lecture seule, Affectation utilisée. */
@Getter
public class AffectationRuleException extends RuntimeException {

    public enum Reason {
        NAME_TAKEN,
        SYSTEM_READ_ONLY,
        IN_USE
    }

    private final Reason reason;

    public AffectationRuleException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
