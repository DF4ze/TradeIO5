package fr.ses10doigts.tradeIO5.model.dto.tree.media;

/**
 * Réponse LLM de la passe 1 (classification, {@code LlmTier.LOW}) —
 * docs/archive/etudes/etude-veille-media-youtube.md §2, prompt supprimé (docs/prompts retiré le 2026-11-10) Lot 2 item A.
 * {@code category} est indicative (à ajuster librement), seule {@code isMarketRelevant} pilote le flux.
 */
public record ClassificationResult(boolean isMarketRelevant, String category) {
}
