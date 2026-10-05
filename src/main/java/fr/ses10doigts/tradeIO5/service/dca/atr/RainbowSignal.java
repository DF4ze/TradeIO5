package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * Signal DÉCOMPOSÉ du Rainbow ATR pour une bougie — jamais de montant : la quantification est faite par un
 * {@link Sizer} propre à l'utilisateur / wallet / actif.
 * <ul>
 *   <li>achat : {@code buyKind} + {@code multZone} (×3 déclenché, ×2, ×1, ×0,5) × {@code buyAthFactor} ;</li>
 *   <li>vente : {@code sellKind} + {@code sellFraction} = fraction de la POSITION RÉELLE à vendre (déjà
 *   multipliée par {@code sellAthFactor}, plafonnée à 1 et à la position hors réserve moon) ;</li>
 *   <li>contexte : zone, mode moon, distance ATH, armements de la bougie ; {@code activeSet} / {@code trend}
 *   renseignés par l'orchestrateur (la machine ne les connaît pas).</li>
 * </ul>
 */
public record RainbowSignal(
        boolean valid, int zone,
        BuyKind buyKind, double multZone, double buyAthFactor,
        SellKind sellKind, double sellFraction, double sellAthFactor,
        boolean moonMode, boolean moonEntry, boolean buyArmedNow, boolean sellArmedNow,
        double athDistance, String activeSet, String trend
) {

    public enum BuyKind { NONE, ZONE, TRIGGERED }

    public enum SellKind { NONE, SELL, MOON_STOP }

    /** Bougie sans bornes calculables (warmup) : aucun signal. */
    public static RainbowSignal invalid(boolean moonMode, boolean moonEntry) {
        return new RainbowSignal(false, -1, BuyKind.NONE, 0, 1.0, SellKind.NONE, 0, 1.0,
                moonMode, moonEntry, false, false, 0.0, null, null);
    }

    public RainbowSignal withContext(String activeSet, String trend) {
        return new RainbowSignal(valid, zone, buyKind, multZone, buyAthFactor, sellKind, sellFraction, sellAthFactor,
                moonMode, moonEntry, buyArmedNow, sellArmedNow, athDistance, activeSet, trend);
    }
}
