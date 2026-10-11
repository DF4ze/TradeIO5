package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import java.util.function.DoubleUnaryOperator;

/**
 * Ce que le signal d'un preset demande ce jour-là, avant tout plafond réel : achat voulu en USDC, vente voulue exprimée
 * en fonction de la position tradable du lien (quantité fixe en mode FIXED, fraction x position en Trend Mix), et clôture.
 * Calculée une fois par preset (simulation partagée), dimensionnée ensuite par lien ({@link LiveSizing}).
 */
public record LiveDemand(double buyAmount, DoubleUnaryOperator sellQtyOfTradable, double close) {

    /** Vente indépendante de la position tradable (quantité voulue par le moteur). */
    public static LiveDemand fixed(double buyAmount, double sellQty, double close) {
        return new LiveDemand(buyAmount, tradable -> sellQty, close);
    }

    /** Quantité à vendre pour une position tradable donnée. */
    public double sellQty(double tradable) {
        return sellQtyOfTradable.applyAsDouble(tradable);
    }
}
