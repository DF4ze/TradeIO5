package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * L3 — ATH de référence d'un ACTIF (pas d'un utilisateur) : valeur + date de la bougie qui l'a posé.
 * Semé une fois sur le long historique puis mis à jour chaque jour ({@link #observe}) ; injecté dans
 * {@link RainbowAtrStrategy#step} car l'indicateur et la machine d'états ne portent aucun historique.
 * <p>
 * Ordre figé : {@link #value()} = ATH de la VEILLE (entrée To the moon « clôture &gt; ATH »),
 * {@link #includingToday(double)} = ATH bougie du jour incluse (distance à l'ATH).
 *
 * @param value      plus haut atteint jusqu'à la veille incluse ; NaN si aucun historique
 * @param timeMillis horodatage (ms) de la bougie qui a posé cet ATH ; 0 si inconnu
 */
public record AthReference(double value, long timeMillis) {

    public static final AthReference NONE = new AthReference(Double.NaN, 0L);

    /** ATH incluant le high de la bougie du jour. */
    public double includingToday(double high) {
        return Double.isNaN(value) ? high : Math.max(value, high);
    }

    /** Référence mise à jour avec la bougie du jour (à appeler une fois la bougie traitée). */
    public AthReference observe(double high, long candleTimeMillis) {
        return Double.isNaN(value) || high > value ? new AthReference(high, candleTimeMillis) : this;
    }
}
