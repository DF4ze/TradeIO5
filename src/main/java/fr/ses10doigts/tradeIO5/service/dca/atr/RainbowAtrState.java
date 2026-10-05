package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * État de la machine du Rainbow ATR entre deux bougies (L2). Immuable ; à persister par (utilisateur, actif)
 * pour l'exécution quotidienne. {@code reserveQty} est une QUANTITÉ (réserve To the moon, posée sur la position
 * de l'utilisateur à l'entrée) ; les NaN signifient « sans objet ».
 *
 * @param lowestSinceArmed   plus bas depuis l'armement achat
 * @param highestSinceArmed  plus haut depuis l'armement vente
 * @param moon               automate To the moon ({@link RainbowMoon})
 * @param prevDown2          borne DOWN2 de la dernière bougie valide (NaN si la précédente était invalide)
 * @param prevClose          clôture de la bougie précédente
 */
public record RainbowAtrState(
        boolean buyArmed, boolean sellArmed, boolean buyLocked,
        double lowestSinceArmed, double highestSinceArmed,
        int buyArmedDays, int sellArmedDays, int cooldown,
        double reserveQty, RainbowMoon.State moon,
        double prevDown2, double prevClose
) {

    public static RainbowAtrState initial() {
        return new RainbowAtrState(false, false, false, Double.NaN, Double.NaN, 0, 0, 0, 0.0,
                RainbowMoon.State.INACTIVE, Double.NaN, Double.NaN);
    }

    public RainbowAtrState withMoon(RainbowMoon.State m) {
        return new RainbowAtrState(buyArmed, sellArmed, buyLocked, lowestSinceArmed, highestSinceArmed,
                buyArmedDays, sellArmedDays, cooldown, reserveQty, m, prevDown2, prevClose);
    }

    /** Bougie sans bornes calculables : la détection de franchissement DOWN2 repart de zéro. */
    public RainbowAtrState afterInvalidBar(RainbowMoon.State m, double close) {
        return new RainbowAtrState(buyArmed, sellArmed, buyLocked, lowestSinceArmed, highestSinceArmed,
                buyArmedDays, sellArmedDays, cooldown, reserveQty, m, Double.NaN, close);
    }
}
