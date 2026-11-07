package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/** Nature d'un événement de l'historique des changements d'Affectation du bench. */
public enum RainbowLivePresetEventType {
    /** Affectation activée par l'utilisateur. */
    ENABLE,
    /** Affectation désactivée par l'utilisateur. */
    DISABLE,
    /** Ancienne duplication d'un preset qui suivait une stratégie (lignes historiques uniquement, plus écrit). */
    DETACH,
    /** Définition ou changement des presets d'une Affectation (le moteur est réinitialisé). */
    LIVE_SWITCH,
    /** Ancienne révision de stratégie suivie (lignes historiques uniquement, plus écrit). */
    STRATEGY_CHANGED
}
