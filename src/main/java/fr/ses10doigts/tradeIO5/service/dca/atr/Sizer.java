package fr.ses10doigts.tradeIO5.service.dca.atr;

import java.math.BigDecimal;

/**
 * L4 — (BigDecimal : montants et quantités) transforme un {@link RainbowSignal} en ordre chiffré. Propre à l'utilisateur / wallet / actif :
 * achat = multiple de la base, vente = fraction de la position réelle ; les plafonds (multiplicateur effectif
 * global, contraintes wallet) vivent dans les implémentations réelles, jamais dans le moteur.
 */
public interface Sizer {

    /**
     * @param position quantité réellement détenue de l'actif (avant l'ordre du jour)
     */
    Order size(RainbowSignal signal, BigDecimal close, BigDecimal position);

    /** Ordre du jour : montant à acheter (unité de la base) et quantité à vendre (unités d'actif), tous deux ≥ 0. */
    record Order(BigDecimal buyAmount, BigDecimal sellQuantity) {
        public static final Order NONE = new Order(BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
