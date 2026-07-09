package fr.ses10doigts.tradeIO5.model.enumerate.tree.macro;

/**
 * Source d'un {@link fr.ses10doigts.tradeIO5.model.dto.tree.macro.MacroEvent}, étude
 * "indicateurs-macro-externes" §14 item G — utile pour le dédoublonnage
 * ({@code MacroEventCalendarService}, qui garde la version Finnhub quand les deux sources
 * rapportent le même événement, car elle porte potentiellement {@code actual} en plus de
 * {@code forecast}/{@code previous}).
 */
public enum MacroEventSource {
    FINNHUB, FOREXFACTORY
}
