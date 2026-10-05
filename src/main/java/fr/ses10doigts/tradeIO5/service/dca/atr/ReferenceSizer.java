package fr.ses10doigts.tradeIO5.service.dca.atr;

import java.math.BigDecimal;

/**
 * Sizer de RÉFÉRENCE, sans plafond ni contrainte de wallet : achat = {@code base × facteurATH × multZone},
 * vente = {@code fraction × position}. Montants et quantités en {@link BigDecimal} (facteurs et fraction du
 * signal, des ratios en double, convertis en leur valeur décimale exacte). Reproduit le pine (comparaison
 * Java/pine) ; n'est pas destiné à passer des ordres réels.
 */
public record ReferenceSizer(BigDecimal baseAmount) implements Sizer {

    @Override
    public Order size(RainbowSignal s, BigDecimal close, BigDecimal position) {
        if (!s.valid()) {
            return Order.NONE;
        }
        BigDecimal buy = s.buyKind() == RainbowSignal.BuyKind.NONE ? BigDecimal.ZERO
                : baseAmount.multiply(BigDecimal.valueOf(s.buyAthFactor())).multiply(BigDecimal.valueOf(s.multZone()));
        BigDecimal sell = s.sellKind() == RainbowSignal.SellKind.NONE ? BigDecimal.ZERO
                : BigDecimal.valueOf(s.sellFraction()).multiply(position);
        return new Order(buy, sell);
    }
}
