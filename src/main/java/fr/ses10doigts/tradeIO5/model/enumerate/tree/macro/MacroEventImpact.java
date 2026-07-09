package fr.ses10doigts.tradeIO5.model.enumerate.tree.macro;

/**
 * Niveau d'impact d'un {@link fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent}, étude
 * "indicateurs-macro-externes" §14 item G. Valeurs alignées sur celles renvoyées par ForexFactory
 * ({@code "Low"|"Medium"|"High"|"Holiday"}, cf. fixture JSON du prompt d'implémentation) ; Finnhub
 * est mappé sur ce même enum (voir {@code FinnhubEconomicCalendarClient}, format non vérifié contre
 * un appel réel).
 */
public enum MacroEventImpact {
    LOW, MEDIUM, HIGH, HOLIDAY
}
