package fr.ses10doigts.tradeIO5.service.optimizer;

/** Mode de coûts du scorer. */
public enum CostMode {
    /** Taux constant par ordre (rapide, pour la recherche). */
    SIMPLIFIED,
    /** {@code OrderReplay} complet : route, lot, minimum d'ordre, spread, slippage, carry (finalistes). */
    COMPLETE
}
