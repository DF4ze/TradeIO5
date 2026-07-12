package fr.ses10doigts.tradeIO5.model.dto.tree.media;

import java.util.List;

/**
 * Réponse LLM de la passe 2 (extraction, {@code LlmTier.MEDIUM}) — 0 à N affirmations de marché
 * extraites du transcript complet (docs/prompt-implementation-veille-media-full.md, Lot 2 item B).
 */
public record ExtractionResult(List<ExtractedClaim> claims) {
}
