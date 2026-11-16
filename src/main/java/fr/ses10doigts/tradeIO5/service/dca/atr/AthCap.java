package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * Plafond utilisateur de la mise ATH, par-dessus les paramètres ATH du preset (qui restent le signal). {@code null} ou valeur
 * nulle = aucun plafond = comportement historique.
 * <ul>
 *   <li>achat : multiplicateur effectif = {@code min(facteurATH × multiplicateurDeZone, buyCap)} (appliqué par {@link ReferenceSizer}) ;</li>
 *   <li>vente : facteur ATH de vente effectif = {@code min(sellAthFac, sellCap)} (appliqué par
 *   {@link RainbowAtrStrategy#stepWithMoon}, avant les bornes position / réserve moon).</li>
 * </ul>
 *
 * @param buyCap  multiple maximal de la base par achat (≥ 1) ; {@code null} = pas de plafond
 * @param sellCap facteur ATH de vente maximal (≥ 0) ; {@code null} = pas de plafond
 */
public record AthCap(Double buyCap, Double sellCap) {

    /** Multiplicateur d'achat effectif ({@code cap} nul ou sans plafond d'achat : inchangé). */
    public static double effectiveBuyMultiplier(AthCap cap, double multiplier) {
        return cap == null || cap.buyCap == null ? multiplier : Math.min(multiplier, cap.buyCap);
    }

    /** Facteur ATH de vente effectif ({@code cap} nul ou sans plafond de vente : inchangé). */
    public static double effectiveSellFactor(AthCap cap, double factor) {
        return cap == null || cap.sellCap == null ? factor : Math.min(factor, cap.sellCap);
    }

    /** Vrai si le plafond d'achat réduit ce multiplicateur. */
    public static boolean buyCapped(AthCap cap, double multiplier) {
        return cap != null && cap.buyCap != null && multiplier > cap.buyCap;
    }

    /** Vrai si aucun des deux plafonds n'est posé. */
    public boolean isEmpty() {
        return buyCap == null && sellCap == null;
    }

    /** Libellé court ({@code achat ×6 / vente ×2}) ; « aucun » si vide. */
    public String label() {
        return isEmpty() ? "aucun" : "achat " + format(buyCap) + " / vente " + format(sellCap);
    }

    private static String format(Double cap) {
        return cap == null ? "—" : "×" + (cap == Math.rint(cap) ? String.valueOf(cap.longValue()) : String.valueOf(cap));
    }
}
