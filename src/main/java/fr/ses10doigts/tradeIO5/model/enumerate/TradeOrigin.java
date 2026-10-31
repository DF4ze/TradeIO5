package fr.ses10doigts.tradeIO5.model.enumerate;

/** Provenance d'un trade enregistré : passé à la main sur l'exchange, ou envoyé par TradeIO (ordre du plan d'exécution). */
public enum TradeOrigin {
    MANUAL,
    TRADEIO
}
