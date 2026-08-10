package fr.ses10doigts.tradeIO5.exceptions;

/**
 * Erreur métier claire levée par
 * {@link fr.ses10doigts.tradeIO5.service.tree.macro.MacroCalendarMcpTools} lorsque l'entrée
 * demandée par l'appelant est invalide (dates non parsables, format différent de
 * {@code yyyy-MM-dd}...). Distincte de {@link DcaException} : même si le patron de validation est
 * identique, les deux exceptions couvrent des domaines sans rapport (DCA vs calendrier macro), cf.
 * prompt d'implémentation "Connecter le calendrier macro au MCP", étape 1 point 1.
 * <p>
 * Volontairement une {@link RuntimeException} simple : elle ne doit jamais laisser fuiter une
 * exception technique non explicite vers un appelant MCP — {@code MacroCalendarMcpTools} capture
 * tout via {@code toJsonOrError} de toute façon, cette exception ne sert qu'à porter un message
 * clair dans {@code error.message}.
 */
public class MacroCalendarException extends RuntimeException {
    public MacroCalendarException(String message) {
        super(message);
    }

    public MacroCalendarException(String message, Throwable cause) {
        super(message, cause);
    }
}
