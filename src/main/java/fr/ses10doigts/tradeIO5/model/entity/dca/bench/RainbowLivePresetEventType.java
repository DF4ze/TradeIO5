package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

/** Nature d'un événement de l'historique des switchs de preset du bench. */
public enum RainbowLivePresetEventType {
    /** Preset activé par l'utilisateur. */
    ENABLE,
    /** Preset désactivé par l'utilisateur. */
    DISABLE,
    /** Duplication d'un preset qui suit une stratégie : la copie perd les mises à jour de System. */
    DETACH,
    /** Bascule du preset live d'un actif (modification du binding). */
    LIVE_SWITCH,
    /** Première passe d'un preset qui suit une stratégie dont la révision a changé. */
    STRATEGY_CHANGED
}
