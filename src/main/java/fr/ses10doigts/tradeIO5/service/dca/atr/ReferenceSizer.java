package fr.ses10doigts.tradeIO5.service.dca.atr;

import java.math.BigDecimal;

/**
 * Sizer de RÉFÉRENCE, sans contrainte de wallet : achat = {@code base × min(facteurATH × multZone, plafond ATH)}
 * (plafond {@link AthCap} optionnel, absent = {@code base × facteurATH × multZone}), vente = {@code fraction × position}. Montants et quantités en {@link BigDecimal} (facteurs et fraction du
 * signal, des ratios en double, convertis en leur valeur décimale exacte). Reproduit le pine (comparaison
 * Java/pine) ; n'est pas destiné à passer des ordres réels.
 */
public record ReferenceSizer(BigDecimal baseAmount, AthCap athCap) implements Sizer {

    /** Sans plafond ATH. */
    public ReferenceSizer(BigDecimal baseAmount) {
        this(baseAmount, null);
    }

    @Override
    public Order size(RainbowSignal s, BigDecimal close, BigDecimal position) {
        if (!s.valid()) {
            return Order.NONE;
        }
        BigDecimal buy = s.buyKind() == RainbowSignal.BuyKind.NONE ? BigDecimal.ZERO : buyAmount(s);
        BigDecimal sell = s.sellKind() == RainbowSignal.SellKind.NONE ? BigDecimal.ZERO
                : BigDecimal.valueOf(s.sellFraction()).multiply(position);
        return new Order(buy, sell);
    }

    /** Mise du signal : plafonnée quand le plafond ATH réduit le multiplicateur, calcul historique sinon. */
    private BigDecimal buyAmount(RainbowSignal s) {
        return AthCap.buyCapped(athCap, s.buyAthFactor() * s.multZone()) ? baseAmount.multiply(BigDecimal.valueOf(athCap.buyCap()))
                : baseAmount.multiply(BigDecimal.valueOf(s.buyAthFactor())).multiply(BigDecimal.valueOf(s.multZone()));
    }
}
