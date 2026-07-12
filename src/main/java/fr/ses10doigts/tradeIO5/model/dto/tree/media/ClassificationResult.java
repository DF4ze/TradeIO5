package fr.ses10doigts.tradeIO5.model.dto.tree.media;

/**
 * Réponse LLM de la passe 1 (classification, {@code LlmTier.LOW}) —
 * docs/etude-veille-media-youtube.md §2, docs/prompt-implementation-veille-media-full.md Lot 2 item A.
 * {@code category} est indicative (à ajuster librement), seule {@code isMarketRelevant} pilote le flux.
 */
public record ClassificationResult(boolean isMarketRelevant, String category) {
}
