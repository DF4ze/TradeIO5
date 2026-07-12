package fr.ses10doigts.tradeIO5.model.enumerate.media;

/**
 * Plateforme d'origine d'une {@link fr.ses10doigts.tradeIO5.model.entity.media.ContentSourceEntity}.
 * Un seul membre aujourd'hui (YouTube), mais un enum plutôt qu'une String libre : le jour où une
 * 2ᵉ plateforme arrive (ex. podcast RSS, Twitter/X), c'est un {@code switch} à compléter côté code,
 * pas une chaîne libre à comparer sans garde-fou (docs/prompt-implementation-veille-media-full.md,
 * Lot 1a).
 */
public enum ContentPlatform {
    YOUTUBE
}
