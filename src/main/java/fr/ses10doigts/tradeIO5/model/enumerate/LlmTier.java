package fr.ses10doigts.tradeIO5.model.enumerate;

/**
 * Niveau logique de capacité/coût demandé pour un appel LLM, découplé du modèle
 * concret utilisé chez le fournisseur ({@link OpenAIModel}). Le mapping niveau → modèle
 * est piloté par la configuration ({@code tradeio.openai.model.low/medium/high}).
 */
public enum LlmTier {
    LOW,
    MEDIUM,
    HIGH
}
