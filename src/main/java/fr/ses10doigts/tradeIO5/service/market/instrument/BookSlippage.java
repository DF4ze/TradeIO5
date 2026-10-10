package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Slippage (%) d'un passage au marché de {@code notional} (en unités de la cotation) dans un carnet : écart entre le prix
 * moyen d'exécution (VWAP) et le meilleur prix du côté pris. Pur.
 * BUY : on dépense {@code notional} en parcourant les asks ; SELL : on vend la quantité de base équivalente
 * ({@code notional / meilleur bid}) en parcourant les bids.
 */
public final class BookSlippage {

    private static final int SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private BookSlippage() {
    }

    /** @return vide si le carnet est trop peu profond pour absorber le montant ou si ses niveaux sont inexploitables */
    public static Optional<BigDecimal> slippagePct(OrderBookSnapshot book, LegSide side, BigDecimal notional) {
        return side == LegSide.BUY ? buy(book.asks(), notional) : sell(book.bids(), notional);
    }

    private static Optional<BigDecimal> buy(List<OrderBookLevel> asks, BigDecimal notional) {
        if (asks.isEmpty() || asks.getFirst().price().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal best = asks.getFirst().price();
        BigDecimal remaining = notional;
        BigDecimal acquired = BigDecimal.ZERO;
        for (OrderBookLevel level : asks) {
            BigDecimal levelNotional = level.price().multiply(level.quantity());
            BigDecimal take = remaining.min(levelNotional);
            acquired = acquired.add(take.divide(level.price(), 18, RoundingMode.HALF_UP));
            remaining = remaining.subtract(take);
            if (remaining.signum() == 0) {
                break;
            }
        }
        if (remaining.signum() > 0 || acquired.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal vwap = notional.divide(acquired, 18, RoundingMode.HALF_UP);
        return Optional.of(vwap.subtract(best).multiply(HUNDRED).divide(best, SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO));
    }

    private static Optional<BigDecimal> sell(List<OrderBookLevel> bids, BigDecimal notional) {
        if (bids.isEmpty() || bids.getFirst().price().signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal best = bids.getFirst().price();
        BigDecimal needed = notional.divide(best, 18, RoundingMode.UP);
        BigDecimal remaining = needed;
        BigDecimal proceeds = BigDecimal.ZERO;
        for (OrderBookLevel level : bids) {
            BigDecimal take = remaining.min(level.quantity());
            proceeds = proceeds.add(take.multiply(level.price()));
            remaining = remaining.subtract(take);
            if (remaining.signum() == 0) {
                break;
            }
        }
        if (remaining.signum() > 0) {
            return Optional.empty();
        }
        BigDecimal vwap = proceeds.divide(needed, 18, RoundingMode.HALF_UP);
        return Optional.of(best.subtract(vwap).multiply(HUNDRED).divide(best, SCALE, RoundingMode.HALF_UP).max(BigDecimal.ZERO));
    }
}
