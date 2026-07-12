package fr.ses10doigts.tradeIO5.model.enumerate.media;

/**
 * Horizon temporel d'un {@link fr.ses10doigts.tradeIO5.model.entity.media.MediaClaimEntity},
 * utilisé pour choisir la demi-vie de décroissance du poids du claim
 * (docs/etude-veille-media-youtube.md §5 : 3j / 3sem / 3mois — valeurs de départ à calibrer).
 */
public enum ClaimHorizon {
    COURT_TERME,
    MOYEN_TERME,
    LONG_TERME
}
