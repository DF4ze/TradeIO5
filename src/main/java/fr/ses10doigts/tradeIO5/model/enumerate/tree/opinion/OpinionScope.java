package fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion;

/**
 * Identifie une opinion métier.
 * Utilisé par le OpinionRegistry.
 */
public enum OpinionScope {
    LOCAL,      // lié à un symbole précis
    GLOBAL,     // macro / marché global
    MACRO,      // économie, liquidité, taux
    EXTERNAL    // LLM, news, sentiment externe
}
