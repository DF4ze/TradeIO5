package fr.ses10doigts.tradeIO5.model.dto.tree.media;

/**
 * Une affirmation de marché telle que renvoyée brute par le LLM (passe 2, extraction) — avant
 * conversion vers {@code MediaClaimEntity}. {@code sentiment}/{@code horizon} sont des
 * {@code String} (pas directement {@code SignalType}/{@code ClaimHorizon}) : le mapping via
 * {@code valueOf(...)} est fait à la persistance, isolé par claim, pour qu'une valeur invalide sur
 * un claim n'invalide pas les autres claims de la même vidéo (docs/prompt-implementation-veille-media-full.md,
 * Lot 2 item B).
 */
public record ExtractedClaim(
        String symbol,
        String sentiment,
        String horizon,
        double confidence,
        String excerpt
) {
}
