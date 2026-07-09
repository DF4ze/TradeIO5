package fr.ses10doigts.tradeIO5.model.dto.market;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carnet d'ordres à cours limité ("market", sans levier) — étude "indicateurs-macro-externes" §6a.
 * {@code bids}/{@code asks} sont triés par proximité du prix courant, comme renvoyé par
 * {@code GET /api/v3/depth} (voir {@code BinanceOrderBookApiClient}).
 */
public record OrderBookSnapshot(List<OrderBookLevel> bids, List<OrderBookLevel> asks) {

    public record OrderBookLevel(BigDecimal price, BigDecimal quantity) {
    }
}
