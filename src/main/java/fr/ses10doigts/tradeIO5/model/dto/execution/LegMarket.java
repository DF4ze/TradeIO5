package fr.ses10doigts.tradeIO5.model.dto.execution;

import java.math.BigDecimal;

/**
 * Données de marché d'une jambe pour un montant donné : frais du compte (%), meilleur bid/ask et slippage au montant (%).
 * {@code slippagePct} vaut 0 quand {@code estimation = TICKER}.
 */
public record LegMarket(BigDecimal feePct, BigDecimal bid, BigDecimal ask, BigDecimal slippagePct,
                        PathEstimation estimation) {
}
