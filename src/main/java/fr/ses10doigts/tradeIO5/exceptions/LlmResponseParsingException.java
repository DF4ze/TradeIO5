package fr.ses10doigts.tradeIO5.exceptions;

/**
 * Levée par {@code OpenAIService#ask(String, LlmTier, String, Class)} quand la réponse LLM est
 * vide ou ne peut pas être désérialisée vers le {@code responseType} demandé. Volontairement une
 * exception dédiée plutôt que de réutiliser {@code LlmAdvice.invalid()} (spécifique au contexte
 * trading BUY/SELL/HOLD, sans sens pour les autres sites d'appel comme la veille média).
 */
public class LlmResponseParsingException extends RuntimeException {
    public LlmResponseParsingException(String message) {
        super(message);
    }

    public LlmResponseParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
