package fr.ses10doigts.tradeIO5.model.enumerate.media;

/**
 * Cycle de vie d'une {@link fr.ses10doigts.tradeIO5.model.entity.media.VideoContentEntity} dans le
 * pipeline de veille média (ingestion → classification → extraction).
 */
public enum VideoContentStatus {
    /** Transcript ingéré, pas encore passé par la classification/extraction LLM (Lot 2). */
    PENDING,
    /** Passe 2 (extraction) terminée avec succès, claims persistés. */
    PROCESSED,
    /** Passe 1 (classification) a jugé le contenu hors-sujet : la passe 2 n'est jamais déclenchée. */
    IRRELEVANT,
    /** Échec technique (pas de transcript disponible, timeout LLM, JSON invalide, etc.) — voir errorReason. */
    ERROR
}
