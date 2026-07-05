package fr.ses10doigts.tradeIO5.exceptions;

/**
 * Erreur métier claire levée par {@link fr.ses10doigts.tradeIO5.service.tree.api.mcp.TreeAnalysisFacade}
 * (et les tools MCP qui l'exposent) lorsqu'une entrée demandée par l'appelant (symbole,
 * IndicatorType, StrategyType, OpinionScope...) est inconnue des registries, ou lorsque la
 * chaîne Indicator → Strategy → Opinion ne peut pas produire de résultat exploitable (ex:
 * aucun OpinionEvent émis).
 * <p>
 * Volontairement une {@link RuntimeException} simple : elle ne doit jamais laisser fuiter une
 * {@link NullPointerException} ou une exception technique non explicite vers un appelant MCP.
 */
public class TreeAnalysisException extends RuntimeException {
    public TreeAnalysisException(String message) {
        super(message);
    }

    public TreeAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
