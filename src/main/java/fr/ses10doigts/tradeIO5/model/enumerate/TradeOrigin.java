package fr.ses10doigts.tradeIO5.model.enumerate;

/** Provenance d'un trade enregistré : passé à la main sur l'exchange, achat récurrent automatique de l'exchange (DCA), ou envoyé par TradeIO (ordre du plan d'exécution). */
public enum TradeOrigin {
    MANUAL,
    DCA,
    TRADEIO
}
