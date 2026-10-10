package fr.ses10doigts.tradeIO5.model.dto.execution;

/** Qualité de l'estimation du spread/slippage : BOOK = carnet au montant, TICKER = meilleur bid/ask seul (slippage inconnu). */
public enum PathEstimation {
    BOOK,
    TICKER
}
