package fr.ses10doigts.tradeIO5.exceptions;

/**
 * Erreur métier claire levée par {@link fr.ses10doigts.tradeIO5.service.dca.DcaCalculatorService}
 * (et le tool MCP qui l'expose, {@link fr.ses10doigts.tradeIO5.service.dca.DcaMcpTools}) lorsque
 * l'entrée demandée par l'appelant est invalide (fréquence non calendaire, dates incohérentes,
 * source de marché inconnue...) ou lorsque le calcul DCA ne peut produire aucun résultat
 * exploitable (aucune bougie disponible pour le symbole sur toute la période).
 * <p>
 * Volontairement une {@link RuntimeException} simple : elle ne doit jamais laisser fuiter une
 * {@link NullPointerException} ou une exception technique non explicite vers un appelant MCP.
 */
public class DcaException extends RuntimeException {
    public DcaException(String message) {
        super(message);
    }

    public DcaException(String message, Throwable cause) {
        super(message, cause);
    }
}
