package fr.ses10doigts.tradeIO5.service.modulation;

/**
 * Modulation de bande, calcul pur (ni base ni horloge). Une exposition proche d'une borne de la bande réduit les quantités
 * qui l'en rapprochent et accentue celles qui l'en éloignent ; au centre (zone libre) rien ne change. Marges de régulation
 * de largeur {@code m % × (max − min)} à l'intérieur de chaque borne, courbe continue {@code l = s²(3 − 2s)}. La marge haute
 * n'existe que si {@code max < 100}, la basse que si {@code min > 0} : la bande neutre {@code [0, 100]} ne module jamais rien.
 * Ne déclenche aucun ordre : il module seulement les montants que le Rainbow demande.
 */
public final class ExposureModulator {

    private static final double EPSILON = 1e-9;
    private static final double PERCENT = 100.0;

    /** Bande utilisateur et paramètres admin : {@code marginPct} en % de la largeur, {@code boost} = G (accentuation max). */
    public record Band(double min, double max, double marginPct, double boost) {

        boolean highActive() {
            return max < ModulationDefaults.EXPOSURE_CEILING_PCT - EPSILON;
        }

        boolean lowActive() {
            return min > ModulationDefaults.EXPOSURE_FLOOR_PCT + EPSILON;
        }

        double margin() {
            return marginPct / PERCENT * (max - min);
        }
    }

    /** Facteurs de bande par sens et intensités {@code l} haute et basse (0 = zone libre, 1 = à la borne). */
    public record Factors(double buyFactor, double sellFactor, double highL, double lowL) {
    }

    /** Facteurs de bande pour une exposition donnée (en %). */
    public static Factors factors(double exposurePct, Band band) {
        double highL = band.highActive() ? smooth((exposurePct - (band.max() - band.margin())) / band.margin()) : 0.0;
        double lowL = band.lowActive() ? smooth(((band.min() + band.margin()) - exposurePct) / band.margin()) : 0.0;
        double boost = band.boost() - 1.0;
        return new Factors((1.0 - highL) * (1.0 + boost * lowL), (1.0 + boost * highL) * (1.0 - lowL), highL, lowL);
    }

    /** Plafond dur d'un achat en USD : {@code max·V − P} (plancher 0) ; illimité si {@code max = 100}. */
    public static double buyCapUsd(double assetsValue, double totalValue, Band band) {
        return band.highActive() ? Math.max(0.0, band.max() / PERCENT * totalValue - assetsValue) : Double.POSITIVE_INFINITY;
    }

    /** Plafond dur d'une vente en USD : {@code P − min·V} (plancher 0) ; illimité si {@code min = 0}. */
    public static double sellCapUsd(double assetsValue, double totalValue, Band band) {
        return band.lowActive() ? Math.max(0.0, assetsValue - band.min() / PERCENT * totalValue) : Double.POSITIVE_INFINITY;
    }

    private static double smooth(double raw) {
        double s = Math.clamp(raw, 0.0, 1.0);
        return s * s * (3.0 - 2.0 * s);
    }

    private ExposureModulator() {
    }
}
