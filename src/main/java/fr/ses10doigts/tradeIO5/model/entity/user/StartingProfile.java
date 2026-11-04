package fr.ses10doigts.tradeIO5.model.entity.user;

/**
 * Profil de départ : pose la bande d'exposition initiale (min/max en %), modifiable ensuite curseur par curseur.
 * {@link #FULL} = toute l'échelle (0 → 100 %). {@link #CUSTOM} n'est jamais choisi : l'utilisateur y bascule dès qu'il
 * modifie lui-même un paramètre de modulation (il n'a pas de bande propre).
 */
public enum StartingProfile {
    PRUDENT(20, 50), BALANCED(30, 70), DYNAMIC(40, 90), FULL(0, 100), CUSTOM(0, 100);

    private final double exposureMin;
    private final double exposureMax;

    StartingProfile(double exposureMin, double exposureMax) {
        this.exposureMin = exposureMin;
        this.exposureMax = exposureMax;
    }

    public double exposureMin() {
        return exposureMin;
    }

    public double exposureMax() {
        return exposureMax;
    }

    /** Vrai pour les profils que l'utilisateur peut choisir (tous sauf {@link #CUSTOM}). */
    public boolean selectable() {
        return this != CUSTOM;
    }
}
