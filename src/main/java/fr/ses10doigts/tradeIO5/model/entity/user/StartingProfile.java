package fr.ses10doigts.tradeIO5.model.entity.user;

/** Profil de départ : pose la bande d'exposition initiale (min/max en %), modifiable ensuite curseur par curseur. */
public enum StartingProfile {
    PRUDENT(20, 50), BALANCED(30, 70), DYNAMIC(40, 90);

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
}
