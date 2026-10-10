package fr.ses10doigts.tradeIO5.model.dto.execution;

import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;

/** Carnet public d'un instrument. {@code TICKER} : un seul niveau par côté (meilleur bid/ask du ticker, repli). */
public record BookResult(OrderBookSnapshot book, PathEstimation estimation) {
}
